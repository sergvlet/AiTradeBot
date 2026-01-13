// src/main/java/com/chicu/aitradebot/strategy/hybrid/HybridStrategyV4.java
package com.chicu.aitradebot.strategy.hybrid;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;

import com.chicu.aitradebot.strategy.ml.MlFeatures;
import com.chicu.aitradebot.strategy.ml.MlPrediction;
import com.chicu.aitradebot.strategy.ml.MlSignalService;

import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import com.chicu.aitradebot.strategy.rl.RlAction;
import com.chicu.aitradebot.strategy.rl.RlAgentService;
import com.chicu.aitradebot.strategy.rl.RlDecision;
import com.chicu.aitradebot.strategy.rl.RlState;

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
 * HYBRID Strategy (V4)
 *
 * Консенсусный вход:
 * - ML даёт pBuy/pSell
 * - RL даёт action + confidence
 * - Евристика: trend up по свечам
 *
 * ВАЖНО:
 * HybridStrategySettings сейчас содержит:
 *  - mlModelKey, rlAgentKey, minConfidence, allowSingleSourceBuy
 * Поэтому:
 *  - общий порог = cfg.minConfidence (и для ML, и для RL)
 *  - lookback берём из StrategySettings.cachedCandlesLimit (или дефолт 200)
 *
 * Выход: только TP/SL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StrategyBinding(StrategyType.HYBRID)
public class HybridStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final long LOG_EVERY_TICKS = 300;

    private final StrategyLivePublisher live;
    private final HybridStrategySettingsService hybridSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    private final CandleProvider candleProvider;
    private final MlSignalService mlSignalService;
    private final RlAgentService rlAgentService;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        HybridStrategySettings cfg;

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
        HybridStrategySettings cfg = hybridSettingsService.getOrCreate(chatId);

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

        int lookback = resolveLookback(ss);
        double thr = normalizeThreshold(doubleOrNull(cfg != null ? cfg.getMinConfidence() : null), 0.60);

        log.info("[HYBRID] ▶ START chatId={} symbol={} thr={} allowSingle={} lookback={} mlKey={} rlKey={}",
                chatId,
                st.symbol,
                round2(thr),
                cfg != null ? String.valueOf(cfg.getAllowSingleSourceBuy()) : "null",
                lookback,
                cfg != null ? safe(cfg.getMlModelKey()) : "null",
                cfg != null ? safe(cfg.getRlAgentKey()) : "null"
        );

        safeLive(() -> live.pushState(chatId, StrategyType.HYBRID, st.symbol, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.HYBRID, st.symbol, null, Signal.hold("started")));
    }

    @Override
    public void stop(Long chatId, String ignored) {

        LocalState st = states.remove(chatId);
        if (st == null) return;

        String sym = st.symbol;
        if (sym != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.HYBRID, sym));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.HYBRID, sym));
            safeLive(() -> live.pushState(chatId, StrategyType.HYBRID, sym, false));
        }

        log.info("[HYBRID] ⏹ STOP chatId={} symbol={} ticks={} inPos={}",
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
        safeLive(() -> live.pushPriceTick(chatId, StrategyType.HYBRID, symFinal, price, time));

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            final StrategySettings ss = st.ss;
            final HybridStrategySettings cfg = st.cfg;

            if (symFinal == null) {
                pushHoldThrottled(chatId, null, st, "no_symbol", time);
                return;
            }
            if (cfg == null) {
                pushHoldThrottled(chatId, symFinal, st, "no_hybrid_settings", time);
                return;
            }
            if (ss == null || ss.getTimeframe() == null || ss.getTimeframe().trim().isEmpty()) {
                pushHoldThrottled(chatId, symFinal, st, "no_timeframe", time);
                return;
            }

            if (st.ticks % LOG_EVERY_TICKS == 0) {
                log.info("[HYBRID] tick chatId={} sym={} price={} inPos={}",
                        chatId, symFinal, fmtBd(price), st.inPosition);
            }

            // 1) EXIT TP/SL
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {
                try {
                    var ex = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.HYBRID,
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

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.HYBRID, symFinal));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.HYBRID, symFinal));
                        safeLive(() -> live.pushSignal(chatId, StrategyType.HYBRID, symFinal, null, Signal.sell(1.0, "tp_sl_exit")));
                        return;
                    }
                } catch (Exception e) {
                    log.error("[HYBRID] ❌ EXIT failed chatId={} err={}", chatId, e.getMessage(), e);
                }
            }

            // 2) ENTRY
            if (!st.inPosition) {

                int lookback = resolveLookback(ss);
                List<CandleProvider.Candle> candles =
                        candleProvider.getRecentCandles(chatId, symFinal, ss.getTimeframe(), lookback);

                if (candles == null || candles.size() < Math.min(30, Math.max(20, lookback / 2))) {
                    pushHoldThrottled(chatId, symFinal, st, "not_enough_candles", time);
                    return;
                }

                // общий порог = cfg.minConfidence (Double)
                double commonThr = normalizeThreshold(doubleOrNull(cfg.getMinConfidence()), 0.60);

                // --- ML ---
                MlPrediction mlPred;
                try {
                    MlFeatures feats = MlFeatures.fromCandles(candles, price);
                    mlPred = mlSignalService.predict(chatId, symFinal, ss.getTimeframe(), feats);
                } catch (Exception e) {
                    pushHoldThrottled(chatId, symFinal, st, "ml_failed", time);
                    return;
                }

                // ВАЖНО: probBuy/probSell у тебя часто BigDecimal → clamp01(BigDecimal)
                double pBuy = (mlPred != null) ? clamp01(mlPred.probBuy()) : 0.0;
                double pSell = (mlPred != null) ? clamp01(mlPred.probSell()) : 0.0;

                boolean mlOk = (pBuy >= commonThr) && (pBuy > pSell);

                // --- RL ---
                RlDecision rlDec;
                try {
                    RlState obs = RlState.fromCandles(candles, price);
                    rlDec = rlAgentService.decide(chatId, symFinal, ss.getTimeframe(), obs);
                } catch (Exception e) {
                    pushHoldThrottled(chatId, symFinal, st, "rl_failed", time);
                    return;
                }

                RlAction action = (rlDec != null && rlDec.action() != null) ? rlDec.action() : RlAction.HOLD;

                // confidence тоже может быть BigDecimal → clamp01(BigDecimal)
                double rlConf = (rlDec != null) ? clamp01(rlDec.confidence()) : 0.0;

                boolean rlOk = (action == RlAction.BUY) && (rlConf >= commonThr);

                // --- heuristics (trend) ---
                boolean trendUp = simpleTrendUp(candles);

                // allowSingleSourceBuy:
                // - true: достаточно (ML ok && trendUp) ИЛИ (RL ok && trendUp)
                // - false: нужен полный консенсус mlOk && rlOk && trendUp
                boolean allowSingle = Boolean.TRUE.equals(cfg.getAllowSingleSourceBuy());

                boolean consensusOk = allowSingle
                        ? (trendUp && (mlOk || rlOk))
                        : (trendUp && mlOk && rlOk);

                if (!consensusOk) {
                    String reason = "no_consensus"
                            + " ml=" + round2(pBuy)
                            + " rl=" + action + ":" + round2(rlConf)
                            + " trendUp=" + trendUp
                            + " thr=" + round2(commonThr)
                            + " single=" + allowSingle;
                    pushHoldThrottled(chatId, symFinal, st, reason, time);
                    return;
                }

                // score = уверенность источников
                double mlPart = mlOk ? pBuy : 0.0;
                double rlPart = rlOk ? rlConf : 0.0;

                double score01;
                if (allowSingle) {
                    score01 = Math.max(mlPart, rlPart);
                } else {
                    score01 = (mlPart + rlPart) / 2.0;
                }

                score01 = Math.min(1.0, Math.max(0.50, score01));
                final double scoreFinal = Math.min(100.0, Math.max(50.0, score01 * 100.0));

                final double pBuyFinal = pBuy;
                final double rlConfFinal = rlConf;

                try {
                    var res = tradeExecutionService.executeEntry(
                            chatId,
                            StrategyType.HYBRID,
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

                    safeLive(() -> live.pushSignal(
                            chatId,
                            StrategyType.HYBRID,
                            symFinal,
                            null,
                            Signal.buy(scoreFinal, "consensus ml=" + round2(pBuyFinal) + " rl=" + round2(rlConfFinal))
                    ));
                    return;

                } catch (Exception e) {
                    log.error("[HYBRID] ❌ BUY failed chatId={} err={}", chatId, e.getMessage(), e);
                    pushHoldThrottled(chatId, symFinal, st, "buy_failed", time);
                    return;
                }
            }

            pushHoldThrottled(chatId, symFinal, st, "in_position", time);
        }
    }

    // =====================================================
    // SETTINGS / LOAD
    // =====================================================

    private static int resolveLookback(StrategySettings ss) {
        Integer fromSs = (ss != null ? ss.getCachedCandlesLimit() : null);
        int look = (fromSs != null && fromSs > 0) ? fromSs : 200;
        if (look < 60) look = 60;
        if (look > 2000) look = 2000;
        return look;
    }

    private void clearPosition(LocalState st) {
        st.inPosition = false;
        st.entryQty = null;
        st.entryPrice = null;
        st.tp = null;
        st.sl = null;
    }

    private void refreshSettingsIfNeeded(Long chatId, LocalState st, Instant now) {

        if (st.lastSettingsLoadAt != null &&
                Duration.between(st.lastSettingsLoadAt, now).compareTo(SETTINGS_REFRESH_EVERY) < 0) {
            return;
        }

        try {
            StrategySettings loaded = loadStrategySettings(chatId);
            HybridStrategySettings cfg = hybridSettingsService.getOrCreate(chatId);

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

                int lookback = resolveLookback(loaded);
                double thr = normalizeThreshold(doubleOrNull(cfg != null ? cfg.getMinConfidence() : null), 0.60);

                log.info("[HYBRID] ⚙️ settings updated chatId={} symbol={} thr={} allowSingle={} lookback={} mlKey={} rlKey={}",
                        chatId,
                        st.symbol,
                        round2(thr),
                        cfg != null ? String.valueOf(cfg.getAllowSingleSourceBuy()) : "null",
                        lookback,
                        cfg != null ? safe(cfg.getMlModelKey()) : "null",
                        cfg != null ? safe(cfg.getRlAgentKey()) : "null"
                );

                String newSymbol = safeUpper(st.symbol);
                if (oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol)) {
                    clearPosition(st);
                    st.lastHoldReason = null;
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[HYBRID] ⚠ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    private String buildFingerprint(StrategySettings ss, HybridStrategySettings cfg) {
        String symbol = ss != null ? safeUpper(ss.getSymbol()) : "null";
        String ex = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String net = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String tf = ss != null ? safe(ss.getTimeframe()) : "null";
        String candles = (ss != null && ss.getCachedCandlesLimit() != null) ? String.valueOf(ss.getCachedCandlesLimit()) : "null";

        String common = cfg != null ? String.valueOf(cfg.getMinConfidence()) : "null";
        String allowSingle = cfg != null ? String.valueOf(cfg.getAllowSingleSourceBuy()) : "null";
        String mlKey = cfg != null ? safe(cfg.getMlModelKey()) : "null";
        String rlKey = cfg != null ? safe(cfg.getRlAgentKey()) : "null";

        return symbol + "|" + ex + "|" + net + "|" + tf + "|" + candles + "|" + common + "|" + allowSingle + "|" + mlKey + "|" + rlKey;
    }

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId, null, null)
                .stream()
                .filter(s -> s.getType() == StrategyType.HYBRID)
                .sorted(
                        Comparator
                                .comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                                .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                )
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("StrategySettings для HYBRID не найдены (chatId=" + chatId + ")"));
    }

    // =====================================================
    // LIVE
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

        safeLive(() -> live.pushSignal(chatId, StrategyType.HYBRID, symbol, null, Signal.hold(reason)));
    }

    // =====================================================
    // CALC
    // =====================================================

    private static boolean simpleTrendUp(List<CandleProvider.Candle> candles) {
        int n = Math.min(20, candles.size());
        if (n < 10) return false;

        double first = candles.get(candles.size() - n).close();
        double last = candles.get(candles.size() - 1).close();

        if (!Double.isFinite(first) || !Double.isFinite(last)) return false;

        return last >= first * 1.0015;
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

    private static String fmtBd(BigDecimal v) {
        if (v == null) return "null";
        return v.stripTrailingZeros().toPlainString();
    }

    private static Double doubleOrNull(Double v) {
        if (v == null) return null;
        if (!Double.isFinite(v)) return null;
        return v;
    }

    private static double normalizeThreshold(Double v, double def) {
        if (v == null) return def;
        double d = v;
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

    // ✅ ВОТ ЭТО — ключевой фикс твоей ошибки: принимаем BigDecimal напрямую
    private static double clamp01(BigDecimal v) {
        if (v == null) return 0.0;
        double d = v.doubleValue();
        return clamp01(d);
    }

    private static String round2(double d) {
        return String.format(Locale.US, "%.2f", d);
    }
}
