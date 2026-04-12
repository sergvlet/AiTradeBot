// src/main/java/com/chicu/aitradebot/strategy/trend/TrendStrategyV4.java
package com.chicu.aitradebot.strategy.trend;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import com.chicu.aitradebot.trade.TradeExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TREND Strategy (V4)
 *
 * Источник истины по общим полям: StrategySettings(type=TREND)
 * Уникальные поля: TrendStrategySettings(emaFastPeriod, emaSlowPeriod, trendThresholdPct, cooldownMs)
 *
 * Логика:
 * - считаем EMA-fast и EMA-slow прямо по price ticks (без CandleService)
 * - если fast > slow на threshold% -> хотим BUY (вход/докупка не делаем, только вход если нет позиции)
 * - если fast < slow на threshold% -> выход по рынку через executeExitIfHit? (нет, это TP/SL)
 *   => здесь: если есть позиция и тренд развернулся, делаем SELL через executeExitIfHit невозможно (оно по цене),
 *      поэтому используем tradeExecutionService.executeExitIfHit только как TP/SL, а “разворот” делаем отдельным exit:
 *      если у тебя есть метод для выхода по сигналу — подключи. Если нет — безопасно делаем HOLD и ждём TP/SL.
 *
 * Поэтому в этой версии:
 * - вход по тренду
 * - выход только по TP/SL (как у DCA), плюс HOLD при развороте.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StrategyBinding(StrategyType.TREND)
public class TrendStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final long LOG_EVERY_TICKS = 300;

    private final StrategyLivePublisher live;
    private final TrendStrategySettingsService trendSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        TrendStrategySettings cfg;

        String symbol;
        String exchange;
        NetworkType network;

        Instant lastSettingsLoadAt;
        String lastFingerprint;

        long ticks;
        long buys;
        long sells;

        // EMA state
        BigDecimal emaFast;
        BigDecimal emaSlow;

        // позиция (как у DCA)
        boolean inPosition;
        BigDecimal entryQty;
        BigDecimal entryPrice;
        BigDecimal tp;
        BigDecimal sl;

        // anti-spam действий
        Instant lastActionAt;

        // hold throttling
        String lastHoldReason;
        Instant lastHoldAt;
    }

    // =====================================================
    // START / STOP
    // =====================================================

    @Override
    public void start(Long chatId, String ignored) {

        StrategySettings ss = loadStrategySettings(chatId);
        TrendStrategySettings cfg = trendSettingsService.getOrCreate(chatId);

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

        log.info("[TREND] ▶ START chatId={} symbol={} fast={} slow={} thrPct={}",
                chatId,
                st.symbol,
                nz(cfg.getEmaFastPeriod(), 9),
                nz(cfg.getEmaSlowPeriod(), 21),
                fmtBd(cfg.getTrendThresholdPct()));

        safeLive(() -> live.pushState(chatId, StrategyType.TREND, st.symbol, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.TREND, st.symbol, null, Signal.hold("started")));
    }

    @Override
    public void stop(Long chatId, String ignored) {

        LocalState st = states.remove(chatId);
        if (st == null) return;

        String sym = st.symbol;

        if (sym != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.TREND, sym));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.TREND, sym));
            safeLive(() -> live.pushState(chatId, StrategyType.TREND, sym, false));
        }

        log.info("[TREND] ⏹ STOP chatId={} symbol={} ticks={} buys={} sells={} inPos={}",
                chatId, sym, st.ticks, st.buys, st.sells, st.inPosition);
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
        safeLive(() -> live.pushPriceTick(chatId, StrategyType.TREND, symFinal, price, time));

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            StrategySettings ss = st.ss;
            TrendStrategySettings cfg = st.cfg;

            if (symFinal == null) {
                pushHoldThrottled(chatId, null, st, "no_symbol", time);
                return;
            }
            if (cfg == null) {
                pushHoldThrottled(chatId, symFinal, st, "no_trend_settings", time);
                return;
            }

            int fastP = nz(cfg.getEmaFastPeriod(), 9);
            int slowP = nz(cfg.getEmaSlowPeriod(), 21);
            if (fastP < 1 || slowP < 2 || fastP >= slowP) {
                pushHoldThrottled(chatId, symFinal, st, "bad_ema_periods", time);
                return;
            }

            BigDecimal thrPct = nzBd(cfg.getTrendThresholdPct(), new BigDecimal("0.10"));
            if (thrPct.signum() <= 0) {
                pushHoldThrottled(chatId, symFinal, st, "threshold<=0", time);
                return;
            }

            int cooldownMs = nz(cfg.getCooldownMs(), 1500);

            // лог раз в N тиков
            if (st.ticks % LOG_EVERY_TICKS == 0) {
                log.info("[TREND] tick chatId={} sym={} price={} emaF={} emaS={} inPos={}",
                        chatId, symFinal, fmtBd(price), fmtBd(st.emaFast), fmtBd(st.emaSlow), st.inPosition);
            }

            // 1) обновляем EMA
            st.emaFast = emaUpdate(st.emaFast, price, fastP);
            st.emaSlow = emaUpdate(st.emaSlow, price, slowP);

            if (st.emaFast == null || st.emaSlow == null || st.emaSlow.signum() <= 0) {
                pushHoldThrottled(chatId, symFinal, st, "ema_warming", time);
                return;
            }

            // 2) EXIT по TP/SL (если в позиции)
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {
                try {
                    var ex = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.TREND,
                            symFinal,
                            price,
                            time,
                            false,
                            st.entryQty,
                            st.tp,
                            st.sl
                    );

                    if (ex.executed()) {
                        st.sells++;

                        log.info("[TREND] ✅ EXIT OK chatId={} sym={} price={} (tp={} sl={})",
                                chatId, symFinal, fmtBd(price), fmtBd(st.tp), fmtBd(st.sl));

                        clearPosition(st);

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.TREND, symFinal));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.TREND, symFinal));
                        safeLive(() -> live.pushSignal(chatId, StrategyType.TREND, symFinal, null, Signal.sell(1.0, "tp_sl_exit")));
                        return;
                    }
                } catch (Exception e) {
                    log.error("[TREND] ❌ EXIT failed chatId={} err={}", chatId, e.getMessage(), e);
                }
            }

            // 3) сигнал тренда: diff% = (emaFast - emaSlow)/emaSlow*100
            BigDecimal diffPct = st.emaFast.subtract(st.emaSlow)
                    .divide(st.emaSlow, 18, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));

            boolean upTrend = diffPct.compareTo(thrPct) >= 0;
            boolean downTrend = diffPct.compareTo(thrPct.negate()) <= 0;

            // 4) cooldown
            if (st.lastActionAt != null) {
                long ms = Duration.between(st.lastActionAt, time).toMillis();
                if (ms < cooldownMs) {
                    pushHoldThrottled(chatId, symFinal, st, "cooldown", time);
                    return;
                }
            }

            // 5) вход только если нет позиции и есть UP тренд
            if (!st.inPosition && upTrend) {

                double score = Math.min(100.0, 50.0 + diffPct.abs().doubleValue() * 10.0);
                if (score < 50.0) score = 50.0;
                final double scoreFinal = score; // важно для лямбды/сигнала

                try {
                    var res = tradeExecutionService.executeEntry(
                            chatId,
                            StrategyType.TREND,
                            symFinal,
                            price,
                            BigDecimal.valueOf(scoreFinal / 100.0),
                            time,
                            ss
                    );

                    if (!res.executed()) {
                        log.info("[TREND] ✋ BUY blocked chatId={} reason={}", chatId, res.reason());
                        pushHoldThrottled(chatId, symFinal, st, res.reason(), time);
                        return;
                    }

                    st.buys++;
                    st.lastActionAt = time;

                    st.inPosition = true;
                    st.entryPrice = res.entryPrice();
                    st.entryQty = res.qty();
                    st.tp = res.tp();
                    st.sl = res.sl();

                    safeLive(() -> live.pushSignal(chatId, StrategyType.TREND, symFinal, null,
                            Signal.buy(scoreFinal, "trend_up")));

                    log.info("[TREND] 🟢 BUY chatId={} sym={} price={} diffPct={} qty={}",
                            chatId, symFinal, fmtBd(price), fmtBd(diffPct), fmtBd(st.entryQty));
                    return;

                } catch (Exception e) {
                    log.error("[TREND] ❌ BUY failed chatId={} err={}", chatId, e.getMessage(), e);
                    pushHoldThrottled(chatId, symFinal, st, "buy_failed", time);
                    return;
                }
            }

            // 6) если тренд вниз — не выходим (в этой версии), просто HOLD
            if (st.inPosition && downTrend) {
                pushHoldThrottled(chatId, symFinal, st, "trend_down_hold", time);
                return;
            }

            pushHoldThrottled(chatId, symFinal, st, upTrend ? "trend_up_wait" : "no_trend", time);
        }
    }

    private void clearPosition(LocalState st) {
        st.inPosition = false;
        st.entryQty = null;
        st.entryPrice = null;
        st.tp = null;
        st.sl = null;
        st.lastActionAt = null;
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
            TrendStrategySettings cfg = trendSettingsService.getOrCreate(chatId);

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

                log.info("[TREND] ⚙️ settings updated chatId={} symbol={} fast={} slow={} thrPct={}",
                        chatId,
                        st.symbol,
                        nz(cfg.getEmaFastPeriod(), 9),
                        nz(cfg.getEmaSlowPeriod(), 21),
                        fmtBd(cfg.getTrendThresholdPct())
                );

                String newSymbol = safeUpper(st.symbol);
                if (oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol)) {
                    // сменили символ — сбрасываем EMA/позицию, чтобы не продолжать “чужую” историю
                    clearPosition(st);
                    st.emaFast = null;
                    st.emaSlow = null;
                    st.lastHoldReason = null;
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[TREND] ⚠ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    private String buildFingerprint(StrategySettings ss, TrendStrategySettings cfg) {
        String symbol = ss != null ? safeUpper(ss.getSymbol()) : "null";
        String ex     = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String net    = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String tf     = ss != null ? safe(ss.getTimeframe()) : "null";
        String candles = ss != null && ss.getCachedCandlesLimit() != null ? String.valueOf(ss.getCachedCandlesLimit()) : "null";

        String f = cfg != null ? String.valueOf(cfg.getEmaFastPeriod()) : "null";
        String s = cfg != null ? String.valueOf(cfg.getEmaSlowPeriod()) : "null";
        String thr = cfg != null ? String.valueOf(cfg.getTrendThresholdPct()) : "null";
        String cd = cfg != null ? String.valueOf(cfg.getCooldownMs()) : "null";

        return symbol + "|" + ex + "|" + net + "|" + tf + "|" + candles + "|" + f + "|" + s + "|" + thr + "|" + cd;
    }

    // =====================================================
    // LOAD StrategySettings(type=TREND)
    // =====================================================

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId)
                .stream()
                .filter(s -> s.getType() == StrategyType.TREND)
                .sorted(
                        Comparator
                                .comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                                .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                )
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "StrategySettings для TREND не найдены (chatId=" + chatId + ")"
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

        safeLive(() -> live.pushSignal(chatId, StrategyType.TREND, symbol, null, Signal.hold(reason)));
    }

    // =====================================================
    // EMA HELPERS
    // =====================================================

    private static BigDecimal emaUpdate(BigDecimal prev, BigDecimal price, int period) {
        if (price == null) return prev;
        if (prev == null) return price;

        // alpha = 2/(period+1)
        BigDecimal alpha = new BigDecimal("2")
                .divide(BigDecimal.valueOf(period + 1L), 18, RoundingMode.HALF_UP);

        return prev.add(alpha.multiply(price.subtract(prev)));
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

    private static BigDecimal nzBd(BigDecimal v, BigDecimal def) {
        return v != null ? v : def;
    }

    private static String fmtBd(BigDecimal v) {
        if (v == null) return "null";
        return v.stripTrailingZeros().toPlainString();
    }
}
