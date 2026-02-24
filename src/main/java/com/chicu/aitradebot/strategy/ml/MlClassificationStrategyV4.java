// src/main/java/com/chicu/aitradebot/strategy/ml/MlClassificationStrategyV4.java
package com.chicu.aitradebot.strategy.ml;

import com.chicu.aitradebot.ai.ml.dto.MlPrediction;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ML_CLASSIFICATION Strategy (V4)
 *
 * Идея:
 * - берём последние свечи через CandleProvider
 * - строим признаки (MlFeatures.fromCandles)
 * - ML /predict -> probBuy/probSell
 * - если ML недоступен -> fallback (простой фильтр), чтобы не было вечного HOLD
 * - вход через TradeExecutionService.executeEntry()
 * - выход по TP/SL через executeExitIfHit()
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StrategyBinding(StrategyType.ML_CLASSIFICATION)
public class MlClassificationStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final long LOG_EVERY_TICKS = 300;

    // HOLD throttle (чтобы UI не засыпать)
    private static final long HOLD_THROTTLE_MS = 2000;

    private final StrategyLivePublisher live;
    private final MlClassificationSettingsService mlSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;
    private final CandleProvider candleProvider;

    /** Реальный ML gateway: HTTP /health + /predict */
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

        // чтобы не спамить логом “ML недоступен” на каждый тик
        Instant lastMlWarnAt;
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

        log.info("[ML_CLASSIFICATION] ▶ START chatId={} symbol={} threshold={} lookback={} modelKey={} schemaHash={}",
                chatId,
                st.symbol,
                fmtBd(cfg.getDecisionThreshold()),
                nz(cfg.getLookbackCandles(), 200),
                safe(cfg.getModelKey()),
                safe(resolveSchemaHash(ss, cfg))
        );

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
                    String ex = (st.exchange != null ? st.exchange : ss.getExchangeName());
                    NetworkType net = (st.network != null ? st.network : ss.getNetworkType());

                    var exRes = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.ML_CLASSIFICATION,
                            symFinal,
                            price,
                            time,
                            true,           // ✅ spot long
                            st.entryQty,
                            st.tp,
                            st.sl,
                            ex,
                            net
                    );

                    if (exRes != null && exRes.executed()) {
                        clearPosition(st);

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.ML_CLASSIFICATION, symFinal));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.ML_CLASSIFICATION, symFinal));
                        safeLive(() -> live.pushSignal(chatId, StrategyType.ML_CLASSIFICATION, symFinal, null,
                                Signal.sell(price.doubleValue(), exRes.tpHit() ? "TP" : "SL")));
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

                List<CandleProvider.Candle> candles =
                        candleProvider.getRecentCandles(chatId, symFinal, ss.getTimeframe(), lookback);

                if (candles == null || candles.size() < Math.min(30, lookback / 2)) {
                    pushHoldThrottled(chatId, symFinal, st, "not_enough_candles", time);
                    return;
                }

                // ✅ фичи
                MlFeatures features = MlFeatures.fromCandles(candles, price);
                Map<String, Object> featureMap = (features != null ? features.toMap() : Map.of());

                double threshold = normalizeThreshold(cfg.getDecisionThreshold());

                // ==========================
                // ML PREDICT (HTTP /predict)
                // ==========================
                MlPrediction pred = null;
                boolean mlOk = mlSignalService.isAvailable();

                if (mlOk) {
                    try {
                        String modelKey = safeEmptyToNull(cfg.getModelKey());
                        String schemaHash = resolveSchemaHash(ss, cfg);

                        pred = mlSignalService.predict(
                                chatId,
                                symFinal,
                                ss.getTimeframe(),
                                modelKey,
                                schemaHash,
                                featureMap
                        );
                    } catch (Exception e) {
                        warnMlOncePer(st, time,
                                "[ML_CLASSIFICATION] ⚠ predict failed chatId=" + chatId +
                                " sym=" + symFinal + " err=" + e.getMessage());
                        pred = null;
                        mlOk = false;
                    }
                }

                double pBuy;
                double pSell;

                if (mlOk && pred != null) {
                    pBuy = clamp01(pred.probBuy());
                    pSell = clamp01(pred.probSell());
                } else {
                    // ===================================
                    // FALLBACK: чтобы реально торговало
                    // ===================================
                    double fb = fallbackBuyScore(features);
                    pBuy = clamp01(fb);
                    pSell = clamp01(1.0 - fb);

                    pushHoldThrottled(chatId, symFinal, st, "ml_off_fallback", time);
                }

                boolean buy = pBuy >= threshold && pBuy > pSell;

                if (!buy) {
                    pushHoldThrottled(chatId, symFinal, st, "no_buy pBuy=" + round2(pBuy), time);
                    return;
                }

                double score = Math.min(100.0, Math.max(50.0, pBuy * 100.0));
                final double scoreFinal = score;

                try {
                    // ⚠️ если у тебя executeEntry требует tpPct/slPct — подгони здесь вызов
                    var res = tradeExecutionService.executeEntry(
                            chatId,
                            StrategyType.ML_CLASSIFICATION,
                            symFinal,
                            price,
                            BigDecimal.valueOf(scoreFinal / 100.0),
                            time,
                            ss
                    );

                    if (res == null || !res.executed()) {
                        pushHoldThrottled(chatId, symFinal, st, res != null ? res.reason() : "entry_null", time);
                        return;
                    }

                    st.inPosition = true;
                    st.entryPrice = res.entryPrice();
                    st.entryQty = res.qty();
                    st.tp = res.tp();
                    st.sl = res.sl();

                    safeLive(() -> live.pushSignal(chatId, StrategyType.ML_CLASSIFICATION, symFinal, null,
                            Signal.buy(price.doubleValue(), "buy pBuy=" + round2(pBuy))));
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

    private void warnMlOncePer(LocalState st, Instant now, String msg) {
        if (st.lastMlWarnAt != null) {
            long ms = Duration.between(st.lastMlWarnAt, now).toMillis();
            if (ms < 15_000) return;
        }
        st.lastMlWarnAt = now;
        log.warn(msg);
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
                log.info("[ML_CLASSIFICATION] ⚙️ settings updated chatId={} symbol={} threshold={} lookback={} modelKey={} schemaHash={}",
                        chatId,
                        st.symbol,
                        fmtBd(cfg.getDecisionThreshold()),
                        cfg.getLookbackCandles(),
                        safe(cfg.getModelKey()),
                        safe(resolveSchemaHash(loaded, cfg))
                );

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
        String candles = (ss != null && ss.getCachedCandlesLimit() != null)
                ? String.valueOf(ss.getCachedCandlesLimit())
                : "null";

        String look = cfg != null ? String.valueOf(cfg.getLookbackCandles()) : "null";
        String thr = cfg != null ? String.valueOf(cfg.getDecisionThreshold()) : "null";
        String model = cfg != null ? safe(cfg.getModelKey()) : "null";
        String schemaHash = safe(resolveSchemaHash(ss, cfg));

        return symbol + "|" + ex + "|" + net + "|" + tf + "|" + candles + "|" + look + "|" + thr + "|" + model + "|" + schemaHash;
    }

    /**
     * schemaHash нужен для версионирования фич/моделей.
     * Источники:
     *  1) MlClassificationSettings.getSchemaHash() (если есть)
     *  2) StrategySettings.getMlSchemaHash()
     *  3) ""
     */
    private String resolveSchemaHash(StrategySettings ss, MlClassificationSettings cfg) {
        if (cfg != null) {
            try {
                var m = cfg.getClass().getMethod("getSchemaHash");
                Object v = m.invoke(cfg);
                String s = (v != null ? String.valueOf(v).trim() : "");
                if (!s.isEmpty()) return s;
            } catch (Exception ignored) {}
        }
        if (ss != null) {
            try {
                String s = ss.getMlSchemaHash();
                if (s != null && !s.trim().isEmpty()) return s.trim();
            } catch (Exception ignored) {}
        }
        return "";
    }

    /**
     * Берём самый свежий StrategySettings для ML_CLASSIFICATION.
     */
    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId)
                .stream()
                .filter(s -> s.getType() == StrategyType.ML_CLASSIFICATION)
                .max(Comparator
                        .comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                )
                .orElseThrow(() -> new IllegalStateException(
                        "StrategySettings для ML_CLASSIFICATION не найдены (chatId=" + chatId + ")"
                ));
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
            if (ms < HOLD_THROTTLE_MS) return;
        }

        st.lastHoldReason = reason;
        st.lastHoldAt = now;
        safeLive(() -> live.pushSignal(chatId, StrategyType.ML_CLASSIFICATION, symbol, null, Signal.hold(reason)));
    }

    // =====================================================
    // FALLBACK (временный, чтобы торговля была даже без ML)
    // =====================================================

    private static double fallbackBuyScore(MlFeatures f) {
        if (f == null) return 0.50;

        double m = safeD(f.momentum1());      // -1..+1 примерно
        double v = safeD(f.volatilityPct());  // 0..N

        double base = 0.50 + (m * 0.25);
        if (v < 0.0005) base -= 0.05;

        return clamp01(base);
    }

    private static double safeD(Double d) {
        if (d == null || !Double.isFinite(d)) return 0.0;
        return d;
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
        return t.isEmpty() ? null : t.toUpperCase(Locale.ROOT);
    }

    private static String safeEmptyToNull(String s) {
        if (s == null) return null;
        String x = s.trim();
        return x.isEmpty() ? null : x;
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
        return String.format(Locale.US, "%.2f", d);
    }
}
