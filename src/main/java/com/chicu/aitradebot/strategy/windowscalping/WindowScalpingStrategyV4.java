package com.chicu.aitradebot.strategy.windowscalping;

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

@StrategyBinding(StrategyType.WINDOW_SCALPING)
@Slf4j
@Component
@RequiredArgsConstructor
public class WindowScalpingStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final long LOG_EVERY_TICKS = 250;

    private final StrategyLivePublisher live;
    private final WindowScalpingStrategySettingsService windowSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        WindowScalpingStrategySettings cfg;

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
        WindowScalpingStrategySettings cfg = windowSettingsService.getOrCreate(chatId);

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

        log.info("[WINDOW] ▶ START chatId={} symbol={} windowSize={} entryLowPct={} minRangePct={}",
                chatId,
                st.symbol,
                cfg.getWindowSize(),
                cfg.getEntryFromLowPct(),
                cfg.getMinRangePct()
        );

        final String sym = st.symbol;
        safeLive(() -> live.pushState(chatId, StrategyType.WINDOW_SCALPING, sym, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.WINDOW_SCALPING, sym, null, Signal.hold("started")));
    }

    @Override
    public void stop(Long chatId, String ignored) {

        LocalState st = states.remove(chatId);
        if (st == null) return;

        String symbol = st.symbol;
        final String sym = symbol;

        if (sym != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.WINDOW_SCALPING, sym));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.WINDOW_SCALPING, sym));
            safeLive(() -> live.pushState(chatId, StrategyType.WINDOW_SCALPING, sym, false));
        }

        log.info("[WINDOW] ⏹ STOP chatId={} symbol={} ticks={} warmups={} entries={} exits={} inPos={}",
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
                log.warn("[WINDOW] ⚠ Invalid price chatId={} price={}", chatId, price);
            }
            return;
        }

        Instant time = ts != null ? ts : Instant.now();

        String tickSymbol = safeUpper(symbolFromTick);
        String cfgSymbol = safeUpper(st.symbol);
        if (cfgSymbol != null && tickSymbol != null && !cfgSymbol.equals(tickSymbol)) return;
        if (cfgSymbol == null && tickSymbol != null) st.symbol = tickSymbol;

        final String symbolForLive = safeUpper(st.symbol);
        safeLive(() -> live.pushPriceTick(chatId, StrategyType.WINDOW_SCALPING, symbolForLive, price, time));

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            StrategySettings ss = st.ss;
            WindowScalpingStrategySettings cfg = st.cfg;
            String symbol = safeUpper(st.symbol);
            final String sym = symbol;

            if (cfg == null) {
                pushHoldThrottled(chatId, sym, st, "no_window_settings", time);
                return;
            }

            int windowSize = cfg.getWindowSize() != null ? cfg.getWindowSize() : 0;
            if (windowSize < 5) {
                pushHoldThrottled(chatId, sym, st, "windowSize<5", time);
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

            if (high == null || low == null || low.signum() <= 0) {
                pushHoldThrottled(chatId, sym, st, "window_invalid", time);
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
                if (st.ticks % LOG_EVERY_TICKS == 0) {
                    log.info("[WINDOW] range too small chatId={} symbol={} rangePct={} minRangePct={}",
                            chatId, sym, fmt(rangePct), fmt(minRangePct));
                }
                pushHoldThrottled(chatId, sym, st, "range_too_small", time);
                return;
            }

            double pos = price.subtract(low)
                    .divide(range, 10, RoundingMode.HALF_UP)
                    .doubleValue();

            double entryLowPct = cfg.getEntryFromLowPct() != null ? cfg.getEntryFromLowPct() : 0.0;
            double entryHighPct = cfg.getEntryFromHighPct() != null ? cfg.getEntryFromHighPct() : 0.0;

            double lowZone = clamp01(entryLowPct / 100.0);
            double highZone = clamp01(1.0 - (entryHighPct / 100.0));

            if (st.ticks % LOG_EVERY_TICKS == 0) {
                log.info("[WINDOW] tick chatId={} symbol={} price={} low={} high={} rangePct={} pos={}",
                        chatId,
                        sym,
                        price.stripTrailingZeros().toPlainString(),
                        low.stripTrailingZeros().toPlainString(),
                        high.stripTrailingZeros().toPlainString(),
                        fmt(rangePct),
                        fmt(pos * 100.0));
            }

            // =====================================================
            // ENTRY (SPOT LONG)
            // =====================================================
            if (!st.inPosition && pos <= lowZone) {

                Integer cooldown = ss != null ? ss.getCooldownSeconds() : null;
                if (cooldown != null && cooldown > 0 && st.lastTradeClosedAt != null) {
                    long passed = Duration.between(st.lastTradeClosedAt, time).getSeconds();
                    if (passed < cooldown) {
                        pushHoldThrottled(chatId, sym, st, "cooldown", time);
                        return;
                    }
                }

                // ✅ FIX: score теперь final и вычисляется без пере-присваивания
                final double score = clamp01(
                        (lowZone <= 0.000001) ? 1.0 : (1.0 - (pos / lowZone))
                ) * 100.0;

                log.info("[WINDOW] ⚡ ENTRY try chatId={} symbol={} price={} posPct={} rangePct={}",
                        chatId,
                        sym,
                        price.stripTrailingZeros().toPlainString(),
                        fmt(pos * 100.0),
                        fmt(rangePct)
                );

                try {
                    var res = tradeExecutionService.executeEntry(
                            chatId,
                            StrategyType.WINDOW_SCALPING,
                            sym,
                            price,
                            BigDecimal.valueOf(pos),
                            time,
                            ss
                    );

                    if (!res.executed()) {
                        log.info("[WINDOW] ✋ ENTRY blocked chatId={} reason={}", chatId, res.reason());
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

                    safeLive(() -> live.pushSignal(chatId, StrategyType.WINDOW_SCALPING, sym, null,
                            Signal.buy(score, "window_low")));

                    st.window.clear();
                    st.lastHoldReason = null;

                } catch (Exception e) {
                    log.error("[WINDOW] ❌ ENTRY failed chatId={} err={}", chatId, e.getMessage(), e);
                    pushHoldThrottled(chatId, sym, st, "entry_failed", time);
                }
            }

            // =====================================================
            // EXIT: TP/SL
            // =====================================================
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {

                try {
                    var ex = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.WINDOW_SCALPING,
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

                        log.info("[WINDOW] ✅ EXIT OK chatId={} price={} (tp={} sl={})",
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

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.WINDOW_SCALPING, sym));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.WINDOW_SCALPING, sym));

                        safeLive(() -> live.pushSignal(chatId, StrategyType.WINDOW_SCALPING, sym, null,
                                Signal.sell(1.0, "tp_sl_exit")));
                    } else {
                        if (pos >= highZone) {
                            pushHoldThrottled(chatId, sym, st, "in_high_zone_wait_tp", time);
                        }
                    }

                } catch (Exception e) {
                    log.error("[WINDOW] ❌ EXIT failed chatId={} err={}", chatId, e.getMessage(), e);
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
            WindowScalpingStrategySettings cfg = windowSettingsService.getOrCreate(chatId);

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

                log.info("[WINDOW] ⚙️ settings updated chatId={} symbol={} windowSize={} entryLowPct={} minRangePct={}",
                        chatId,
                        st.symbol,
                        cfg != null ? cfg.getWindowSize() : null,
                        cfg != null ? cfg.getEntryFromLowPct() : null,
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
            log.warn("[WINDOW] ⚠️ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    private String buildFingerprint(StrategySettings ss, WindowScalpingStrategySettings cfg) {
        String symbol = ss != null ? safeUpper(ss.getSymbol()) : "null";
        String ex     = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String net    = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String tf     = ss != null ? safe(ss.getTimeframe()) : "null";
        String candles = ss != null && ss.getCachedCandlesLimit() != null ? String.valueOf(ss.getCachedCandlesLimit()) : "null";
        String cooldown = ss != null && ss.getCooldownSeconds() != null ? String.valueOf(ss.getCooldownSeconds()) : "null";

        String w = cfg != null ? String.valueOf(cfg.getWindowSize()) : "null";
        String low = cfg != null ? String.valueOf(cfg.getEntryFromLowPct()) : "null";
        String high = cfg != null ? String.valueOf(cfg.getEntryFromHighPct()) : "null";
        String minR = cfg != null ? String.valueOf(cfg.getMinRangePct()) : "null";

        return symbol + "|" + ex + "|" + net + "|" + tf + "|" + candles + "|" + cooldown
               + "|" + w + "|" + low + "|" + high + "|" + minR;
    }

    // =====================================================
    // LOAD StrategySettings
    // =====================================================

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId, null, null)
                .stream()
                .filter(s -> s.getType() == StrategyType.WINDOW_SCALPING)
                .sorted(
                        Comparator
                                .comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                                .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                )
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "StrategySettings для WINDOW_SCALPING не найдены (chatId=" + chatId + ")"
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

        safeLive(() -> live.pushSignal(chatId, StrategyType.WINDOW_SCALPING, symbol, null, Signal.hold(reason)));
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
