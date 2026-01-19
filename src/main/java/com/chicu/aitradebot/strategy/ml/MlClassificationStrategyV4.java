// src/main/java/com/chicu/aitradebot/strategy/ai/MlClassificationStrategyV4.java
package com.chicu.aitradebot.strategy.ml;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import com.chicu.aitradebot.trade.TradeExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ML_CLASSIFICATION Strategy (V4)
 *
 * Идея:
 * - Берём последние свечи через CandleProvider
 * - Формируем признаки (feature builder — внутри)
 * - Вызываем ML-предиктор (MlSignalService) -> probBuy/probSell
 * - Если probBuy >= threshold -> BUY (executeEntry)
 * - Выход только по TP/SL (executeExitIfHit)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StrategyBinding(StrategyType.ML_CLASSIFICATION)
public class MlClassificationStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final long LOG_EVERY_TICKS = 300;

    private final StrategyLivePublisher live;
    private final MlClassificationSettingsService mlSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    private final CandleProvider candleProvider;

    /** Тонкий интерфейс — подставишь свою реализацию (PythonInferenceService/ML gateway и т.д.) */
    private final MlSignalService mlSignalService;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        MlClassificationSettings cfg;

        String symbol;
        String exchange;
        NetworkType network;

        Instant lastSettingsLoadAt;
        String lastFingerprint;

        long ticks;

        boolean inPosition;
        BigDecimal entryQty;
        BigDecimal entryPrice;
        BigDecimal tp;
        BigDecimal sl;

        String lastHoldReason;
        Instant lastHoldAt;
    }

    // =====================================================
    // START / STOP
    // =====================================================

    @Override
    public void start(Long chatId, String ignored) {
        StrategySettings ss = loadStrategySettings(chatId);
        MlClassificationSettings cfg = mlSettingsService.getOrCreate(chatId);

        LocalState st = new LocalState();
        st.active = true;
        st.startedAt = Instant.now();
        st.ss = ss;
        st.cfg = cfg;

        st.symbol = safeUpper(ss.getSymbol());
        st.exchange = ss.getExchangeName();
        st.network = ss.getNetworkType();

        st.lastSettingsLoadAt = Instant.now();
        st.lastFingerprint = buildFingerprint(ss, cfg);

        states.put(chatId, st);

        log.info("[ML_CLASSIFICATION] ▶ START chatId={} symbol={} threshold={} lookback={}",
                chatId, st.symbol, fmtBd(cfg.getDecisionThreshold()), nz(cfg.getLookbackCandles(), 200));

        safeLive(() -> live.pushState(chatId, StrategyType.ML_CLASSIFICATION, st.symbol, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.ML_CLASSIFICATION, st.symbol, null, Signal.hold("started")));
    }

    @Override
    public void stop(Long chatId, String ignored) {
        LocalState st = states.remove(chatId);
        if (st == null) return;

        String sym = st.symbol;
        if (sym != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.ML_CLASSIFICATION, sym));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.ML_CLASSIFICATION, sym));
            safeLive(() -> live.pushState(chatId, StrategyType.ML_CLASSIFICATION, sym, false));
        }

        log.info("[ML_CLASSIFICATION] ⏹ STOP chatId={} symbol={} ticks={} inPos={}",
                chatId, sym, st.ticks, st.inPosition);
    }

    @Override
    public boolean isActive(Long chatId) {
        LocalState st = states.get(chatId);
        return st != null && st.active;
    }

    @Override
    public Instant getStartedAt(Long chatId) {
        LocalState st = states.get(chatId);
        return st != null ? st.startedAt : null;
    }

    // =====================================================
    // PRICE UPDATE
    // =====================================================

    @Override
    public void onPriceUpdate(Long chatId, String symbolFromTick, BigDecimal price, Instant ts) {

        LocalState st = states.get(chatId);
        if (st == null || !st.active) return;

        st.ticks++;

        if (price == null || price.signum() <= 0) return;
        Instant time = (ts != null ? ts : Instant.now());

        String tickSym = safeUpper(symbolFromTick);
        String cfgSym = safeUpper(st.symbol);
        if (cfgSym != null && tickSym != null && !cfgSym.equals(tickSym)) return;
        if (cfgSym == null && tickSym != null) st.symbol = tickSym;

        final String symFinal = safeUpper(st.symbol);
        safeLive(() -> live.pushPriceTick(chatId, StrategyType.ML_CLASSIFICATION, symFinal, price, time));

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            final StrategySettings ss = st.ss;
            final MlClassificationSettings cfg = st.cfg;

            if (symFinal == null) {
                pushHoldThrottled(chatId, null, st, "no_symbol", time);
                return;
            }
            if (cfg == null) {
                pushHoldThrottled(chatId, symFinal, st, "no_ml_settings", time);
                return;
            }
            if (ss == null || ss.getTimeframe() == null || ss.getTimeframe().trim().isEmpty()) {
                pushHoldThrottled(chatId, symFinal, st, "no_timeframe", time);
                return;
            }

            if (st.ticks % LOG_EVERY_TICKS == 0) {
                log.info("[ML_CLASSIFICATION] tick chatId={} sym={} price={} inPos={}",
                        chatId, symFinal, fmtBd(price), st.inPosition);
            }

            // 1) EXIT TP/SL
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {
                try {
                    var ex = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.ML_CLASSIFICATION,
                            symFinal,
                            price,
                            time,
                            false,
                            st.entryQty,
                            st.tp,
                            st.sl
                    );

                    if (ex.executed()) {
                        clearPosition(st);

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.ML_CLASSIFICATION, symFinal));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.ML_CLASSIFICATION, symFinal));
                        safeLive(() -> live.pushSignal(chatId, StrategyType.ML_CLASSIFICATION, symFinal, null, Signal.sell(1.0, "tp_sl_exit")));
                        return;
                    }
                } catch (Exception e) {
                    log.error("[ML_CLASSIFICATION] ❌ EXIT failed chatId={} err={}", chatId, e.getMessage(), e);
                }
            }

            // 2) ENTRY (только если не в позиции)
            if (!st.inPosition) {

                int lookback = nz(cfg.getLookbackCandles(), 200);
                if (lookback < 50) lookback = 50;

                var candles = candleProvider.getRecentCandles(chatId, symFinal, ss.getTimeframe(), lookback);
                if (candles == null || candles.size() < Math.min(30, lookback / 2)) {
                    pushHoldThrottled(chatId, symFinal, st, "not_enough_candles", time);
                    return;
                }

                // признаки (минимальные, чтобы было что кормить модели)
                MlFeatures features = MlFeatures.fromCandles(candles, price);

                MlPrediction pred;
                try {
                    pred = mlSignalService.predict(chatId, symFinal, ss.getTimeframe(), features);
                } catch (Exception e) {
                    log.warn("[ML_CLASSIFICATION] ⚠ predict failed chatId={} sym={} err={}", chatId, symFinal, e.toString());
                    pushHoldThrottled(chatId, symFinal, st, "predict_failed", time);
                    return;
                }

                if (pred == null) {
                    pushHoldThrottled(chatId, symFinal, st, "predict_null", time);
                    return;
                }

                double threshold = normalizeThreshold(cfg.getDecisionThreshold());
                double pBuy = clamp01(pred.probBuy());
                double pSell = clamp01(pred.probSell());

                // простое решение: если BUY сильно выше SELL и выше threshold
                boolean buy = pBuy >= threshold && pBuy > pSell;

                if (!buy) {
                    pushHoldThrottled(chatId, symFinal, st, "no_ml_buy", time);
                    return;
                }

                double score = Math.min(100.0, Math.max(50.0, pBuy * 100.0));
                final double scoreFinal = score;

                try {
                    var res = tradeExecutionService.executeEntry(
                            chatId,
                            StrategyType.ML_CLASSIFICATION,
                            symFinal,
                            price,
                            BigDecimal.valueOf(scoreFinal / 100.0),
                            time,
                            ss
                    );

                    if (!res.executed()) {
                        pushHoldThrottled(chatId, symFinal, st, res.reason(), time);
                        return;
                    }

                    st.inPosition = true;
                    st.entryPrice = res.entryPrice();
                    st.entryQty = res.qty();
                    st.tp = res.tp();
                    st.sl = res.sl();

                    safeLive(() -> live.pushSignal(chatId, StrategyType.ML_CLASSIFICATION, symFinal, null,
                            Signal.buy(scoreFinal, "ml_buy pBuy=" + round2(pBuy))));
                    return;

                } catch (Exception e) {
                    log.error("[ML_CLASSIFICATION] ❌ BUY failed chatId={} err={}", chatId, e.getMessage(), e);
                    pushHoldThrottled(chatId, symFinal, st, "buy_failed", time);
                    return;
                }
            }

            pushHoldThrottled(chatId, symFinal, st, "in_position", time);
        }
    }

    private void clearPosition(LocalState st) {
        st.inPosition = false;
        st.entryQty = null;
        st.entryPrice = null;
        st.tp = null;
        st.sl = null;
    }

    // =====================================================
    // SETTINGS
    // =====================================================

    private void refreshSettingsIfNeeded(Long chatId, LocalState st, Instant now) {
        if (st.lastSettingsLoadAt != null &&
                Duration.between(st.lastSettingsLoadAt, now).compareTo(SETTINGS_REFRESH_EVERY) < 0) {
            return;
        }

        try {
            StrategySettings loaded = loadStrategySettings(chatId);
            MlClassificationSettings cfg = mlSettingsService.getOrCreate(chatId);

            String fp = buildFingerprint(loaded, cfg);
            boolean changed = st.lastFingerprint == null || !Objects.equals(st.lastFingerprint, fp);

            String oldSymbol = safeUpper(st.symbol);

            st.ss = loaded;
            st.cfg = cfg;

            st.symbol = safeUpper(loaded.getSymbol());
            st.exchange = loaded.getExchangeName();
            st.network = loaded.getNetworkType();

            st.lastSettingsLoadAt = now;

            if (changed) {
                st.lastFingerprint = fp;
                log.info("[ML_CLASSIFICATION] ⚙️ settings updated chatId={} symbol={} threshold={} lookback={}",
                        chatId, st.symbol, fmtBd(cfg.getDecisionThreshold()), cfg.getLookbackCandles());

                String newSymbol = safeUpper(st.symbol);
                if (oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol)) {
                    clearPosition(st);
                    st.lastHoldReason = null;
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[ML_CLASSIFICATION] ⚠ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    private String buildFingerprint(StrategySettings ss, MlClassificationSettings cfg) {
        String symbol = ss != null ? safeUpper(ss.getSymbol()) : "null";
        String ex = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String net = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String tf = ss != null ? safe(ss.getTimeframe()) : "null";
        String candles = (ss != null && ss.getCachedCandlesLimit() != null) ? String.valueOf(ss.getCachedCandlesLimit()) : "null";

        String look = cfg != null ? String.valueOf(cfg.getLookbackCandles()) : "null";
        String thr = cfg != null ? String.valueOf(cfg.getDecisionThreshold()) : "null";
        String model = cfg != null ? safe(cfg.getModelKey()) : "null";

        return symbol + "|" + ex + "|" + net + "|" + tf + "|" + candles + "|" + look + "|" + thr + "|" + model;
    }

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId, null, null)
                .stream()
                .filter(s -> s.getType() == StrategyType.ML_CLASSIFICATION)
                .sorted(
                        Comparator
                                .comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                                .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                )
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("StrategySettings для ML_CLASSIFICATION не найдены (chatId=" + chatId + ")"));
    }

    // =====================================================
    // LIVE HELPERS
    // =====================================================

    private void safeLive(Runnable r) {
        try { r.run(); } catch (Exception ignored) {}
    }

    private void pushHoldThrottled(Long chatId, String symbol, LocalState st, String reason, Instant now) {
        if (symbol == null) return;

        if (Objects.equals(st.lastHoldReason, reason) && st.lastHoldAt != null) {
            long ms = Duration.between(st.lastHoldAt, now).toMillis();
            if (ms < 2000) return;
        }

        st.lastHoldReason = reason;
        st.lastHoldAt = now;
        safeLive(() -> live.pushSignal(chatId, StrategyType.ML_CLASSIFICATION, symbol, null, Signal.hold(reason)));
    }

    // =====================================================
    // UTILS
    // =====================================================

    private static String safe(String s) {
        return s == null ? "null" : s.trim();
    }

    private static String safeUpper(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t.toUpperCase();
    }

    private static int nz(Integer v, int def) {
        return v != null ? v : def;
    }

    private static String fmtBd(BigDecimal v) {
        if (v == null) return "null";
        return v.stripTrailingZeros().toPlainString();
    }

    private static double normalizeThreshold(BigDecimal v) {
        if (v == null) return 0.65;
        double d = v.doubleValue();
        // если хранишь в процентах 65 вместо 0.65
        if (d > 1.0 && d <= 100.0) d = d / 100.0;
        if (d < 0.50) d = 0.50;
        if (d > 0.95) d = 0.95;
        return d;
    }

    private static double clamp01(double d) {
        if (!Double.isFinite(d)) return 0.0;
        if (d < 0.0) return 0.0;
        if (d > 1.0) return 1.0;
        return d;
    }

    private static String round2(double d) {
        return String.format(java.util.Locale.US, "%.2f", d);
    }
}
