
// src/main/java/com/chicu/aitradebot/strategy/smartfusion/SmartFusionStrategyV4.java
package com.chicu.aitradebot.strategy.smartfusion;

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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SMART_FUSION Strategy (V4)

 * Источники:
 * - TECH: RSI + EMA crossover (даёт techScore [0..1])
 * - ML  : confidence BUY [0..1]
 * - RL  : action + confidence -> rlScore [0..1] только при BUY

 * Итог:
 * score = wTech*tech + wMl*ml + wRl*rl
 * BUY если score >= threshold и не в позиции.
 * SELL сигналим (не выходим по рынку) — выход по TP/SL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StrategyBinding(StrategyType.SMART_FUSION)
public class SmartFusionStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final long LOG_EVERY_TICKS = 300;

    private final StrategyLivePublisher live;
    private final SmartFusionStrategySettingsService sfSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    private final CandleProvider candleProvider;

    // можно подложить заглушки, пока ML/RL не готовы
    private final SmartFusionMlService mlService;
    private final SmartFusionRlService rlService;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        SmartFusionStrategySettings cfg;

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
        SmartFusionStrategySettings cfg = sfSettingsService.getOrCreate(chatId);

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

        log.info("[SMART_FUSION] ▶ START chatId={} symbol={} tf={} thr={} (wTech={} wMl={} wRl={})",
                chatId, st.symbol, ss.getTimeframe(),
                fmtBd(cfg.getDecisionThreshold()),
                fmtBd(cfg.getWeightTech()), fmtBd(cfg.getWeightMl()), fmtBd(cfg.getWeightRl())
        );

        safeLive(() -> live.pushState(chatId, StrategyType.SMART_FUSION, st.symbol, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.SMART_FUSION, st.symbol, null, Signal.hold("started")));
    }

    @Override
    public void stop(Long chatId, String ignored) {

        LocalState st = states.remove(chatId);
        if (st == null) return;

        String sym = st.symbol;

        if (sym != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.SMART_FUSION, sym));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.SMART_FUSION, sym));
            safeLive(() -> live.pushState(chatId, StrategyType.SMART_FUSION, sym, false));
        }

        log.info("[SMART_FUSION] ⏹ STOP chatId={} symbol={} ticks={} inPos={}",
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
        safeLive(() -> live.pushPriceTick(chatId, StrategyType.SMART_FUSION, symFinal, price, time));

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            final StrategySettings ss = st.ss;
            final SmartFusionStrategySettings cfg = st.cfg;

            if (symFinal == null) {
                pushHoldThrottled(chatId, null, st, "no_symbol", time);
                return;
            }
            if (cfg == null) {
                pushHoldThrottled(chatId, symFinal, st, "no_smartfusion_settings", time);
                return;
            }
            if (ss == null || ss.getTimeframe() == null || ss.getTimeframe().trim().isEmpty()) {
                pushHoldThrottled(chatId, symFinal, st, "no_timeframe", time);
                return;
            }

            if (st.ticks % LOG_EVERY_TICKS == 0) {
                log.info("[SMART_FUSION] tick chatId={} sym={} price={} inPos={}",
                        chatId, symFinal, fmtBd(price), st.inPosition);
            }

            // 1) EXIT TP/SL
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {
                try {
                    var ex = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.SMART_FUSION,
                            symFinal,
                            price,
                            time,
                            false,
                            st.entryQty,
                            st.tp,
                            st.sl
                    );

                    if (ex.executed()) {
                        log.info("[SMART_FUSION] ✅ EXIT OK chatId={} sym={} price={} (tp={} sl={})",
                                chatId, symFinal, fmtBd(price), fmtBd(st.tp), fmtBd(st.sl));

                        clearPosition(st);

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.SMART_FUSION, symFinal));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.SMART_FUSION, symFinal));
                        safeLive(() -> live.pushSignal(chatId, StrategyType.SMART_FUSION, symFinal, null, Signal.sell(1.0, "tp_sl_exit")));
                        return;
                    }
                } catch (Exception e) {
                    log.error("[SMART_FUSION] ❌ EXIT failed chatId={} err={}", chatId, e.getMessage(), e);
                }
            }

            // 2) Данные
            int lookback = clamp(nz(cfg.getLookbackCandles(), 250), 80, 2000);
            List<CandleProvider.Candle> candles = candleProvider.getRecentCandles(chatId, symFinal, ss.getTimeframe(), lookback);
            if (candles == null || candles.size() < Math.min(60, lookback / 2)) {
                pushHoldThrottled(chatId, symFinal, st, "not_enough_candles", time);
                return;
            }

            SmartFusionFeatures features = SmartFusionFeatures.fromCandles(candles, price);

            // 3) TECH score
            double techScore = computeTechScore(candles, cfg);

            // 4) ML score
            double minSrc = norm01(cfg.getMinSourceConfidence(), 0.55);
            double mlScore;
            try {
                double mlBuy = mlService != null
                        ? mlService.predictBuyConfidence(chatId, cfg.getMlModelKey(), symFinal, ss.getTimeframe(), features)
                        : 0.0;
                mlBuy = clamp01(mlBuy);
                mlScore = (mlBuy >= minSrc) ? mlBuy : 0.0;
            } catch (Exception ignored) {
                mlScore = 0.0;
            }

            // 5) RL score
            double rlScore = 0.0;
            try {
                SmartFusionRlService.RlDecision d = rlService != null
                        ? rlService.decide(chatId, cfg.getRlAgentKey(), symFinal, ss.getTimeframe(), features)
                        : null;

                if (d != null) {
                    String act = d.action() != null ? d.action().trim().toUpperCase() : "HOLD";
                    double conf = clamp01(d.confidence());
                    if (conf >= minSrc && "BUY".equals(act)) {
                        rlScore = conf;
                    } else {
                        rlScore = 0.0;
                    }
                }
            } catch (Exception ignored) {
                rlScore = 0.0;
            }

            // 6) Итоговый score
            double wTech = norm01(cfg.getWeightTech(), 0.50);
            double wMl = norm01(cfg.getWeightMl(), 0.25);
            double wRl = norm01(cfg.getWeightRl(), 0.25);

            double sumW = wTech + wMl + wRl;
            if (sumW <= 0.000001) sumW = 1.0;

            double fused = (wTech * techScore + wMl * mlScore + wRl * rlScore) / sumW;

            double thr = norm01(cfg.getDecisionThreshold(), 0.65);

            boolean buySignal = fused >= thr;
            boolean sellHint = techScore < 0.30 && (mlScore == 0.0) && (rlScore == 0.0); // мягкий “остыли”

            // 7) ENTRY
            if (!st.inPosition && buySignal) {

                // score для executeEntry = fused (0..1) -> %
                double scorePct;
                scorePct = Math.min(100.0, Math.max(50.0, fused * 100.0));
                final double scoreFinal = scorePct;

                try {
                    var res = tradeExecutionService.executeEntry(
                            chatId,
                            StrategyType.SMART_FUSION,
                            symFinal,
                            price,
                            BigDecimal.valueOf(scoreFinal / 100.0),
                            time,
                            ss
                    );

                    if (!res.executed()) {
                        log.info("[SMART_FUSION] ✋ BUY blocked chatId={} reason={}", chatId, res.reason());
                        pushHoldThrottled(chatId, symFinal, st, res.reason(), time);
                        return;
                    }

                    st.inPosition = true;
                    st.entryPrice = res.entryPrice();
                    st.entryQty = res.qty();
                    st.tp = res.tp();
                    st.sl = res.sl();

                    String reason = "fused=" + round2(fused) +
                            " tech=" + round2(techScore) +
                            " ml=" + round2(mlScore) +
                            " rl=" + round2(rlScore);

                    safeLive(() -> live.pushSignal(chatId, StrategyType.SMART_FUSION, symFinal, null, Signal.buy(scoreFinal, reason)));
                    return;

                } catch (Exception e) {
                    log.error("[SMART_FUSION] ❌ BUY failed chatId={} err={}", chatId, e.getMessage(), e);
                    pushHoldThrottled(chatId, symFinal, st, "buy_failed", time);
                    return;
                }
            }

            // 8) В позиции — можем только информировать
            if (st.inPosition && sellHint) {
                pushHoldThrottled(chatId, symFinal, st, "sell_hint fused=" + round2(fused), time);
                return;
            }

            pushHoldThrottled(chatId, symFinal, st,
                    "hold fused=" + round2(fused) + " tech=" + round2(techScore),
                    time);
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
    // TECH: RSI + EMA crossover -> score [0..1]
    // =====================================================

    private static double computeTechScore(List<CandleProvider.Candle> candles, SmartFusionStrategySettings cfg) {

        int rsiPeriod = clamp(nz(cfg.getRsiPeriod(), 14), 5, 50);
        int emaFast = clamp(nz(cfg.getEmaFast(), 9), 3, 50);
        int emaSlow = clamp(nz(cfg.getEmaSlow(), 21), emaFast + 1, 200);

        double rsiBuyBelow = safeDouble(cfg.getRsiBuyBelow(), 35.0);
        double rsiSellAbove = safeDouble(cfg.getRsiSellAbove(), 65.0);

        double rsi = calcRsi(candles, rsiPeriod);
        double emaF = calcEma(candles, emaFast);
        double emaS = calcEma(candles, emaSlow);

        if (!Double.isFinite(rsi) || !Double.isFinite(emaF) || !Double.isFinite(emaS)) return 0.0;

        boolean emaBull = emaF > emaS;
        boolean rsiOversold = rsi <= rsiBuyBelow;
        boolean rsiOverbought = rsi >= rsiSellAbove;

        // базовый score
        double score = 0.0;

        if (emaBull) score += 0.50;            // тренд
        if (rsiOversold) score += 0.50;        // “дешево” для входа
        if (rsiOverbought) score -= 0.30;      // перегрето — режем score

        if (score < 0.0) score = 0.0;

        return score;
    }

    private static double calcEma(List<CandleProvider.Candle> candles, int period) {
        int n = candles.size();
        if (n < period + 2) return Double.NaN;

        double k = 2.0 / (period + 1.0);

        // старт с SMA
        double sma = 0.0;
        for (int i = n - period; i < n; i++) sma += candles.get(i).close();
        sma /= period;

        double ema = sma;
        // прогоняем последние period*2 (стабильнее)
        int start = Math.max(0, n - period * 2);
        for (int i = start; i < n; i++) {
            double c = candles.get(i).close();
            ema = c * k + ema * (1.0 - k);
        }
        return ema;
    }

    private static double calcRsi(List<CandleProvider.Candle> candles, int period) {
        int n = candles.size();
        if (n < period + 2) return Double.NaN;

        double gain = 0.0;
        double loss = 0.0;

        int start = n - period - 1;
        for (int i = start + 1; i < n; i++) {
            double prev = candles.get(i - 1).close();
            double cur = candles.get(i).close();
            double ch = cur - prev;
            if (ch > 0) gain += ch;
            else loss += (-ch);
        }

        if (loss == 0.0 && gain == 0.0) return 50.0;
        if (loss == 0.0) return 100.0;

        double rs = gain / loss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    // =====================================================
    // SETTINGS REFRESH
    // =====================================================

    private void refreshSettingsIfNeeded(Long chatId, LocalState st, Instant now) {

        if (st.lastSettingsLoadAt != null &&
                Duration.between(st.lastSettingsLoadAt, now).compareTo(SETTINGS_REFRESH_EVERY) < 0) {
            return;
        }

        try {
            StrategySettings loaded = loadStrategySettings(chatId);
            SmartFusionStrategySettings cfg = sfSettingsService.getOrCreate(chatId);

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

                log.info("[SMART_FUSION] ⚙️ settings updated chatId={} symbol={} thr={} lookback={} rsi={} ema={}/{}",
                        chatId,
                        st.symbol,
                        fmtBd(cfg.getDecisionThreshold()),
                        cfg.getLookbackCandles(),
                        cfg.getRsiPeriod(),
                        cfg.getEmaFast(),
                        cfg.getEmaSlow());

                String newSymbol = safeUpper(st.symbol);
                if (oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol)) {
                    clearPosition(st);
                    st.lastHoldReason = null;
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[SMART_FUSION] ⚠ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    private String buildFingerprint(StrategySettings ss, SmartFusionStrategySettings cfg) {

        String symbol = ss != null ? safeUpper(ss.getSymbol()) : "null";
        String ex = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String net = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String tf = ss != null ? safe(ss.getTimeframe()) : "null";
        String candles = (ss != null && ss.getCachedCandlesLimit() != null) ? String.valueOf(ss.getCachedCandlesLimit()) : "null";

        String look = cfg != null ? String.valueOf(cfg.getLookbackCandles()) : "null";
        String thr = cfg != null ? String.valueOf(cfg.getDecisionThreshold()) : "null";

        String wT = cfg != null ? String.valueOf(cfg.getWeightTech()) : "null";
        String wM = cfg != null ? String.valueOf(cfg.getWeightMl()) : "null";
        String wR = cfg != null ? String.valueOf(cfg.getWeightRl()) : "null";

        String rsiP = cfg != null ? String.valueOf(cfg.getRsiPeriod()) : "null";
        String rsiB = cfg != null ? String.valueOf(cfg.getRsiBuyBelow()) : "null";
        String rsiS = cfg != null ? String.valueOf(cfg.getRsiSellAbove()) : "null";
        String emaF = cfg != null ? String.valueOf(cfg.getEmaFast()) : "null";
        String emaS = cfg != null ? String.valueOf(cfg.getEmaSlow()) : "null";

        String ml = cfg != null ? safe(cfg.getMlModelKey()) : "null";
        String rl = cfg != null ? safe(cfg.getRlAgentKey()) : "null";

        String minC = cfg != null ? String.valueOf(cfg.getMinSourceConfidence()) : "null";

        return symbol + "|" + ex + "|" + net + "|" + tf + "|" + candles + "|" +
                look + "|" + thr + "|" + wT + "|" + wM + "|" + wR + "|" +
                rsiP + "|" + rsiB + "|" + rsiS + "|" + emaF + "|" + emaS + "|" +
                ml + "|" + rl + "|" + minC;
    }

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId, null, null)
                .stream()
                .filter(s -> s.getType() == StrategyType.SMART_FUSION).max(Comparator
                        .comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed()
                        .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElseThrow(() -> new IllegalStateException(
                        "StrategySettings для SMART_FUSION не найдены (chatId=" + chatId + ")"
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
            if (ms < 2000) return;
        }

        st.lastHoldReason = reason;
        st.lastHoldAt = now;

        safeLive(() -> live.pushSignal(chatId, StrategyType.SMART_FUSION, symbol, null, Signal.hold(reason)));
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

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp01(double d) {
        if (!Double.isFinite(d)) return 0.0;
        if (d < 0.0) return 0.0;
        return Math.min(d, 1.0);
    }

    private static double norm01(BigDecimal v, double def) {
        if (v == null) return def;
        double d = v.doubleValue();
        // если кто-то хранит 65 вместо 0.65
        if (d > 1.0 && d <= 100.0) d = d / 100.0;
        return clamp01(d);
    }

    private static double safeDouble(BigDecimal v, double def) {
        if (v == null) return def;
        double d = v.doubleValue();
        return Double.isFinite(d) ? d : def;
    }

    private static String fmtBd(BigDecimal v) {
        if (v == null) return "null";
        return v.stripTrailingZeros().toPlainString();
    }

    private static String round2(double d) {
        return String.format(java.util.Locale.US, "%.2f", d);
    }
}
