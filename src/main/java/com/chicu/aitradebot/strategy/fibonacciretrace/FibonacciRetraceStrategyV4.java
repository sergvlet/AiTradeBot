package com.chicu.aitradebot.strategy.fibonacciretrace;

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
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@StrategyBinding(StrategyType.FIBONACCI_RETRACE)
@Slf4j
@Component
@RequiredArgsConstructor
public class FibonacciRetraceStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final long LOG_EVERY_TICKS = 250;

    private final StrategyLivePublisher live;
    private final FibonacciRetraceStrategySettingsService fiboSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        FibonacciRetraceStrategySettings cfg;

        String symbol;
        String exchange;
        NetworkType network;

        Instant lastSettingsLoadAt;
        String lastFingerprint;

        Deque<BigDecimal> window = new ArrayDeque<>();

        boolean inPosition;
        boolean isLong;

        BigDecimal entryPrice;
        BigDecimal tp;
        BigDecimal sl;
        BigDecimal entryQty;
        Long entryOrderId;

        Instant lastTradeClosedAt;

        long ticks;
        long warmups;
        long entries;
        long exits;

        String lastHoldReason;
        Instant lastHoldAt;
    }

    // =====================================================
    // START / STOP
    // =====================================================

    @Override
    public void start(Long chatId, String ignored) {

        StrategySettings ss = loadStrategySettings(chatId);
        FibonacciRetraceStrategySettings cfg = fiboSettingsService.getOrCreate(chatId);

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

        log.info("[FIBO-RETRACE] ▶ START chatId={} symbol={} window={} entryLevel={} tolPct={} minRangePct={}",
                chatId,
                st.symbol,
                cfg.getWindowSize(),
                cfg.getEntryLevel(),
                cfg.getEntryTolerancePct(),
                cfg.getMinRangePct()
        );

        final String sym = st.symbol;
        safeLive(() -> live.pushState(chatId, StrategyType.FIBONACCI_RETRACE, sym, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.FIBONACCI_RETRACE, sym, null, Signal.hold("started")));
    }

    @Override
    public void stop(Long chatId, String ignored) {

        LocalState st = states.remove(chatId);
        if (st == null) return;

        final String sym = st.symbol;

        if (sym != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.FIBONACCI_RETRACE, sym));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.FIBONACCI_RETRACE, sym));
            safeLive(() -> live.pushState(chatId, StrategyType.FIBONACCI_RETRACE, sym, false));
        }

        log.info("[FIBO-RETRACE] ⏹ STOP chatId={} symbol={} ticks={} warmups={} entries={} exits={} inPos={}",
                chatId, sym, st.ticks, st.warmups, st.entries, st.exits, st.inPosition);
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

        if (price == null || price.signum() <= 0) {
            if (st.ticks % LOG_EVERY_TICKS == 0) {
                log.warn("[FIBO-RETRACE] ⚠ Invalid price chatId={} price={}", chatId, price);
            }
            return;
        }

        Instant time = ts != null ? ts : Instant.now();

        String tickSymbol = safeUpper(symbolFromTick);
        String cfgSymbol = safeUpper(st.symbol);
        if (cfgSymbol != null && tickSymbol != null && !cfgSymbol.equals(tickSymbol)) return;
        if (cfgSymbol == null && tickSymbol != null) st.symbol = tickSymbol;

        final String symbolForLive = safeUpper(st.symbol);
        safeLive(() -> live.pushPriceTick(chatId, StrategyType.FIBONACCI_RETRACE, symbolForLive, price, time));

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            StrategySettings ss = st.ss;
            FibonacciRetraceStrategySettings cfg = st.cfg;
            final String sym = safeUpper(st.symbol);

            if (cfg == null || !cfg.isEnabled()) {
                pushHoldThrottled(chatId, sym, st, "disabled_or_no_settings", time);
                return;
            }

            int windowSize = cfg.getWindowSize() != null ? cfg.getWindowSize() : 0;
            if (windowSize < 30) {
                pushHoldThrottled(chatId, sym, st, "windowSize<30", time);
                return;
            }

            st.window.addLast(price);
            while (st.window.size() > windowSize) st.window.removeFirst();

            if (st.window.size() < windowSize) {
                st.warmups++;
                pushHoldThrottled(chatId, sym, st, "warming_up", time);
                return;
            }

            BigDecimal high = null;
            BigDecimal low = null;
            for (BigDecimal p : st.window) {
                if (p == null) continue;
                high = (high == null) ? p : high.max(p);
                low  = (low == null) ? p : low.min(p);
            }

            if (high == null || low.signum() <= 0) {
                pushHoldThrottled(chatId, sym, st, "invalid_swing", time);
                return;
            }

            BigDecimal range = high.subtract(low);
            if (range.signum() <= 0) {
                pushHoldThrottled(chatId, sym, st, "range_zero", time);
                return;
            }

            double rangePct = range
                    .divide(low, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();

            double minRangePct = cfg.getMinRangePct() != null ? cfg.getMinRangePct() : 0.0;
            if (rangePct < minRangePct) {
                pushHoldThrottled(chatId, sym, st, "range_too_small", time);
                return;
            }

            // Сетка (uptrend сценарий): fibPrice = high - range*level
            double level = cfg.getEntryLevel() != null ? cfg.getEntryLevel() : 0.618;
            level = clamp01(level);

            BigDecimal fibPrice = high.subtract(range.multiply(BigDecimal.valueOf(level)));

            double tolPct = cfg.getEntryTolerancePct() != null ? cfg.getEntryTolerancePct() : 0.0;
            BigDecimal tol = fibPrice
                    .multiply(BigDecimal.valueOf(tolPct))
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);

            BigDecimal upper = fibPrice.add(tol);
            BigDecimal lower = fibPrice.subtract(tol);

            boolean inZone = price.compareTo(lower) >= 0 && price.compareTo(upper) <= 0;

            // invalidation: пробой low вниз на X%
            double invPct = cfg.getInvalidateBelowLowPct() != null ? cfg.getInvalidateBelowLowPct() : 0.0;
            BigDecimal invBand = low
                    .multiply(BigDecimal.valueOf(invPct))
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);

            boolean invalidated = price.compareTo(low.subtract(invBand)) <= 0;

            if (st.ticks % LOG_EVERY_TICKS == 0) {
                log.info("[FIBO-RETRACE] tick chatId={} symbol={} price={} low={} high={} level={} fib={} tolPct={} inZone={} invalidated={}",
                        chatId,
                        sym,
                        price.stripTrailingZeros().toPlainString(),
                        low.stripTrailingZeros().toPlainString(),
                        high.stripTrailingZeros().toPlainString(),
                        fmt(level),
                        fibPrice.stripTrailingZeros().toPlainString(),
                        fmt(tolPct),
                        inZone,
                        invalidated
                );
            }

            if (invalidated && !st.inPosition) {
                pushHoldThrottled(chatId, sym, st, "invalidated_below_low", time);
                st.window.clear();
                return;
            }

            // =====================================================
            // ENTRY (SPOT LONG): вход на ретрейсе к уровню
            // =====================================================
            if (!st.inPosition && inZone) {

                Integer cooldown = ss != null ? ss.getCooldownSeconds() : null;
                if (cooldown != null && cooldown > 0 && st.lastTradeClosedAt != null) {
                    long passed = Duration.between(st.lastTradeClosedAt, time).getSeconds();
                    if (passed < cooldown) {
                        pushHoldThrottled(chatId, sym, st, "cooldown", time);
                        return;
                    }
                }

                // score 0..100: чем ближе к fibPrice тем выше
                double dist = price.subtract(fibPrice).abs()
                        .divide(fibPrice, 10, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .doubleValue();
                double score = clamp01(1.0 - (dist / Math.max(0.000001, tolPct))) * 100.0;

                log.info("[FIBO-RETRACE] ⚡ ENTRY try chatId={} symbol={} price={} fib={} score={}",
                        chatId,
                        sym,
                        price.stripTrailingZeros().toPlainString(),
                        fibPrice.stripTrailingZeros().toPlainString(),
                        fmt(score)
                );

                try {
                    var res = tradeExecutionService.executeEntry(
                            chatId,
                            StrategyType.FIBONACCI_RETRACE,
                            sym,
                            price,
                            BigDecimal.valueOf(score / 100.0),
                            time,
                            ss
                    );

                    if (!res.executed()) {
                        log.info("[FIBO-RETRACE] ✋ ENTRY blocked chatId={} reason={}", chatId, res.reason());
                        pushHoldThrottled(chatId, sym, st, res.reason(), time);
                        return;
                    }

                    st.entries++;
                    st.inPosition = true;
                    st.isLong = true;

                    st.entryPrice = res.entryPrice();
                    st.tp = res.tp();
                    st.sl = res.sl();
                    st.entryQty = res.qty();
                    st.entryOrderId = res.orderId();

                    safeLive(() -> live.pushSignal(chatId, StrategyType.FIBONACCI_RETRACE, sym, null,
                            Signal.buy(score, "fibo_retrace_entry")));

                    st.window.clear();
                    st.lastHoldReason = null;

                } catch (Exception e) {
                    log.error("[FIBO-RETRACE] ❌ ENTRY failed chatId={} err={}", chatId, e.getMessage(), e);
                    pushHoldThrottled(chatId, sym, st, "entry_failed", time);
                }

                return;
            }

            if (!st.inPosition) {
                pushHoldThrottled(chatId, sym, st, "no_entry", time);
            }

            // =====================================================
            // EXIT: TP/SL через TradeExecutionService
            // =====================================================
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {
                try {
                    var ex = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.FIBONACCI_RETRACE,
                            sym,
                            price,
                            time,
                            true,
                            st.entryQty,
                            st.tp,
                            st.sl
                    );

                    if (ex.executed()) {
                        st.exits++;

                        log.info("[FIBO-RETRACE] ✅ EXIT OK chatId={} price={} (tp={} sl={})",
                                chatId,
                                price.stripTrailingZeros().toPlainString(),
                                st.tp.stripTrailingZeros().toPlainString(),
                                st.sl.stripTrailingZeros().toPlainString()
                        );

                        st.inPosition = false;
                        st.entryQty = null;
                        st.entryOrderId = null;
                        st.entryPrice = null;
                        st.tp = null;
                        st.sl = null;

                        st.lastTradeClosedAt = time;

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.FIBONACCI_RETRACE, sym));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.FIBONACCI_RETRACE, sym));

                        safeLive(() -> live.pushSignal(chatId, StrategyType.FIBONACCI_RETRACE, sym, null,
                                Signal.sell(1.0, "tp_sl_exit")));
                    }

                } catch (Exception e) {
                    log.error("[FIBO-RETRACE] ❌ EXIT failed chatId={} err={}", chatId, e.getMessage(), e);
                }
            }
        }
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
            FibonacciRetraceStrategySettings cfg = fiboSettingsService.getOrCreate(chatId);

            String fp = buildFingerprint(loaded, cfg);
            boolean changed = st.lastFingerprint == null || !Objects.equals(st.lastFingerprint, fp);

            String oldSymbol = safeUpper(st.symbol);

            if (loaded != null) st.ss = loaded;
            if (cfg != null) st.cfg = cfg;

            if (loaded != null) {
                String loadedSymbol = safeUpper(loaded.getSymbol());
                if (loadedSymbol != null) st.symbol = loadedSymbol;

                if (loaded.getExchangeName() != null) st.exchange = loaded.getExchangeName();
                if (loaded.getNetworkType() != null) st.network = loaded.getNetworkType();
            }

            st.lastSettingsLoadAt = now;

            if (changed) {
                st.lastFingerprint = fp;

                log.info("[FIBO-RETRACE] ⚙️ settings updated chatId={} symbol={} window={} level={} tolPct={} minRangePct={}",
                        chatId,
                        st.symbol,
                        cfg != null ? cfg.getWindowSize() : null,
                        cfg != null ? cfg.getEntryLevel() : null,
                        cfg != null ? cfg.getEntryTolerancePct() : null,
                        cfg != null ? cfg.getMinRangePct() : null
                );

                String newSymbol = safeUpper(st.symbol);
                if (oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol)) {
                    st.window.clear();
                    st.lastHoldReason = null;
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[FIBO-RETRACE] ⚠️ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    private String buildFingerprint(StrategySettings ss, FibonacciRetraceStrategySettings cfg) {
        String symbol = ss != null ? safeUpper(ss.getSymbol()) : "null";
        String ex     = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String net    = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String tf     = ss != null ? safe(ss.getTimeframe()) : "null";
        String candles = ss != null && ss.getCachedCandlesLimit() != null ? String.valueOf(ss.getCachedCandlesLimit()) : "null";
        String cooldown = ss != null && ss.getCooldownSeconds() != null ? String.valueOf(ss.getCooldownSeconds()) : "null";

        String w = cfg != null ? String.valueOf(cfg.getWindowSize()) : "null";
        String minR = cfg != null ? String.valueOf(cfg.getMinRangePct()) : "null";
        String lvl = cfg != null ? String.valueOf(cfg.getEntryLevel()) : "null";
        String tol = cfg != null ? String.valueOf(cfg.getEntryTolerancePct()) : "null";
        String inv = cfg != null ? String.valueOf(cfg.getInvalidateBelowLowPct()) : "null";
        String en  = cfg != null ? String.valueOf(cfg.isEnabled()) : "null";

        return symbol + "|" + ex + "|" + net + "|" + tf + "|" + candles + "|" + cooldown
                + "|" + w + "|" + minR + "|" + lvl + "|" + tol + "|" + inv + "|" + en;
    }

    // =====================================================
    // LOAD StrategySettings
    // =====================================================

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId, null, null)
                .stream()
                .filter(s -> s.getType() == StrategyType.FIBONACCI_RETRACE).max(Comparator
                        .comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed()
                        .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElseThrow(() -> new IllegalStateException(
                        "StrategySettings для FIBONACCI_RETRACE не найдены (chatId=" + chatId + ")"
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

        safeLive(() -> live.pushSignal(chatId, StrategyType.FIBONACCI_RETRACE, symbol, null, Signal.hold(reason)));
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

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }
}
