package com.chicu.aitradebot.strategy.priceaction;

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

@StrategyBinding(StrategyType.PRICE_ACTION)
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceActionStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final long LOG_EVERY_TICKS = 250;

    private final StrategyLivePublisher live;
    private final PriceActionStrategySettingsService paSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        PriceActionStrategySettings cfg;

        String symbol;
        String exchange;
        NetworkType network;

        Instant lastSettingsLoadAt;
        String lastFingerprint;

        Deque<BigDecimal> window = new ArrayDeque<>();

        // простая модель "подтверждения" пробоя
        int aboveHighConfirm;
        int belowLowConfirm;

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
        PriceActionStrategySettings cfg = paSettingsService.getOrCreate(chatId);

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

        log.info("[PRICE-ACTION] ▶ START chatId={} symbol={} window={} minRangePct={} breakoutPct={} confirmTicks={}",
                chatId,
                st.symbol,
                cfg.getWindowSize(),
                cfg.getMinRangePct(),
                cfg.getBreakoutOfRangePct(),
                cfg.getConfirmTicks()
        );

        final String sym = st.symbol;
        safeLive(() -> live.pushState(chatId, StrategyType.PRICE_ACTION, sym, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.PRICE_ACTION, sym, null, Signal.hold("started")));
    }

    @Override
    public void stop(Long chatId, String ignored) {

        LocalState st = states.remove(chatId);
        if (st == null) return;

        final String sym = st.symbol;

        if (sym != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.PRICE_ACTION, sym));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.PRICE_ACTION, sym));
            safeLive(() -> live.pushState(chatId, StrategyType.PRICE_ACTION, sym, false));
        }

        log.info("[PRICE-ACTION] ⏹ STOP chatId={} symbol={} ticks={} warmups={} entries={} exits={} inPos={}",
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
                log.warn("[PRICE-ACTION] ⚠ Invalid price chatId={} price={}", chatId, price);
            }
            return;
        }

        Instant time = ts != null ? ts : Instant.now();

        String tickSymbol = safeUpper(symbolFromTick);
        String cfgSymbol = safeUpper(st.symbol);
        if (cfgSymbol != null && tickSymbol != null && !cfgSymbol.equals(tickSymbol)) return;
        if (cfgSymbol == null && tickSymbol != null) st.symbol = tickSymbol;

        final String symbolForLive = safeUpper(st.symbol);
        safeLive(() -> live.pushPriceTick(chatId, StrategyType.PRICE_ACTION, symbolForLive, price, time));

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            StrategySettings ss = st.ss;
            PriceActionStrategySettings cfg = st.cfg;
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

            if (high == null || low == null || low.signum() <= 0) {
                pushHoldThrottled(chatId, sym, st, "invalid_window", time);
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
                st.aboveHighConfirm = 0;
                st.belowLowConfirm = 0;
                pushHoldThrottled(chatId, sym, st, "flat_range", time);
                return;
            }

            // breakout threshold: high + range * breakoutPct/100
            double breakoutPct = cfg.getBreakoutOfRangePct() != null ? cfg.getBreakoutOfRangePct() : 0.0;
            BigDecimal breakoutDelta = range
                    .multiply(BigDecimal.valueOf(breakoutPct))
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);

            BigDecimal upBreak = high.add(breakoutDelta);
            BigDecimal downBreak = low.subtract(breakoutDelta);

            boolean above = price.compareTo(upBreak) >= 0;
            boolean below = price.compareTo(downBreak) <= 0;

            int needConfirm = cfg.getConfirmTicks() != null ? cfg.getConfirmTicks() : 1;
            if (needConfirm < 1) needConfirm = 1;

            if (above) {
                st.aboveHighConfirm++;
                st.belowLowConfirm = 0;
            } else if (below) {
                st.belowLowConfirm++;
                st.aboveHighConfirm = 0;
            } else {
                st.aboveHighConfirm = 0;
                st.belowLowConfirm = 0;
            }

            if (st.ticks % LOG_EVERY_TICKS == 0) {
                log.info("[PRICE-ACTION] tick chatId={} symbol={} price={} low={} high={} rangePct={} upBreak={} dnBreak={} confUp={} confDn={}",
                        chatId,
                        sym,
                        price.stripTrailingZeros().toPlainString(),
                        low.stripTrailingZeros().toPlainString(),
                        high.stripTrailingZeros().toPlainString(),
                        fmt(rangePct),
                        upBreak.stripTrailingZeros().toPlainString(),
                        downBreak.stripTrailingZeros().toPlainString(),
                        st.aboveHighConfirm,
                        st.belowLowConfirm
                );
            }

            // =====================================================
            // ENTRY: пробой вверх (лонг)
            // =====================================================
            if (!st.inPosition && st.aboveHighConfirm >= needConfirm) {

                Integer cooldown = ss != null ? ss.getCooldownSeconds() : null;
                if (cooldown != null && cooldown > 0 && st.lastTradeClosedAt != null) {
                    long passed = Duration.between(st.lastTradeClosedAt, time).getSeconds();
                    if (passed < cooldown) {
                        pushHoldThrottled(chatId, sym, st, "cooldown", time);
                        return;
                    }
                }

                // score 0..100: насколько далеко ушли за уровень пробоя
                double overPct = price.subtract(upBreak)
                        .divide(price, 10, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .doubleValue();
                double score = clamp01(overPct / Math.max(0.000001, breakoutPct)) * 100.0;

                log.info("[PRICE-ACTION] ⚡ ENTRY LONG chatId={} symbol={} price={} score={}",
                        chatId, sym, price.stripTrailingZeros().toPlainString(), fmt(score));

                try {
                    var res = tradeExecutionService.executeEntry(
                            chatId,
                            StrategyType.PRICE_ACTION,
                            sym,
                            price,
                            BigDecimal.valueOf(score / 100.0),
                            time,
                            ss
                    );

                    if (!res.executed()) {
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

                    safeLive(() -> live.pushSignal(chatId, StrategyType.PRICE_ACTION, sym, null,
                            Signal.buy(score, "breakout_up")));

                    st.window.clear();
                    st.aboveHighConfirm = 0;
                    st.belowLowConfirm = 0;
                    st.lastHoldReason = null;

                } catch (Exception e) {
                    log.error("[PRICE-ACTION] ❌ ENTRY failed chatId={} err={}", chatId, e.getMessage(), e);
                    pushHoldThrottled(chatId, sym, st, "entry_failed", time);
                }

                return;
            }

            // =====================================================
            // (опционально) шорт не делаем — если хочешь, добавим позже
            // =====================================================
            if (!st.inPosition && st.belowLowConfirm >= needConfirm) {
                pushHoldThrottled(chatId, sym, st, "down_break_detected_spot_long_only", time);
                st.belowLowConfirm = 0;
            }

            if (!st.inPosition) {
                pushHoldThrottled(chatId, sym, st, "no_entry", time);
            }

            // =====================================================
            // EXIT: TP/SL
            // =====================================================
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {
                try {
                    var ex = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.PRICE_ACTION,
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

                        log.info("[PRICE-ACTION] ✅ EXIT OK chatId={} price={} (tp={} sl={})",
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

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.PRICE_ACTION, sym));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.PRICE_ACTION, sym));

                        safeLive(() -> live.pushSignal(chatId, StrategyType.PRICE_ACTION, sym, null,
                                Signal.sell(1.0, "tp_sl_exit")));
                    }

                } catch (Exception e) {
                    log.error("[PRICE-ACTION] ❌ EXIT failed chatId={} err={}", chatId, e.getMessage(), e);
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
            PriceActionStrategySettings cfg = paSettingsService.getOrCreate(chatId);

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

                log.info("[PRICE-ACTION] ⚙️ settings updated chatId={} symbol={} window={} minRangePct={} breakoutPct={} confirmTicks={}",
                        chatId,
                        st.symbol,
                        cfg != null ? cfg.getWindowSize() : null,
                        cfg != null ? cfg.getMinRangePct() : null,
                        cfg != null ? cfg.getBreakoutOfRangePct() : null,
                        cfg != null ? cfg.getConfirmTicks() : null
                );

                String newSymbol = safeUpper(st.symbol);
                if (oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol)) {
                    st.window.clear();
                    st.aboveHighConfirm = 0;
                    st.belowLowConfirm = 0;
                    st.lastHoldReason = null;
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[PRICE-ACTION] ⚠️ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    private String buildFingerprint(StrategySettings ss, PriceActionStrategySettings cfg) {
        String symbol = ss != null ? safeUpper(ss.getSymbol()) : "null";
        String ex     = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String net    = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String tf     = ss != null ? safe(ss.getTimeframe()) : "null";
        String candles = ss != null && ss.getCachedCandlesLimit() != null ? String.valueOf(ss.getCachedCandlesLimit()) : "null";
        String cooldown = ss != null && ss.getCooldownSeconds() != null ? String.valueOf(ss.getCooldownSeconds()) : "null";

        String w = cfg != null ? String.valueOf(cfg.getWindowSize()) : "null";
        String minR = cfg != null ? String.valueOf(cfg.getMinRangePct()) : "null";
        String br = cfg != null ? String.valueOf(cfg.getBreakoutOfRangePct()) : "null";
        String wick = cfg != null ? String.valueOf(cfg.getMaxWickPctOfRange()) : "null";
        String c = cfg != null ? String.valueOf(cfg.getConfirmTicks()) : "null";
        String en = cfg != null ? String.valueOf(cfg.isEnabled()) : "null";

        return symbol + "|" + ex + "|" + net + "|" + tf + "|" + candles + "|" + cooldown
                + "|" + w + "|" + minR + "|" + br + "|" + wick + "|" + c + "|" + en;
    }

    // =====================================================
    // LOAD StrategySettings
    // =====================================================

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId, null, null)
                .stream()
                .filter(s -> s.getType() == StrategyType.PRICE_ACTION)
                .sorted(
                        Comparator
                                .comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                                .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                )
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "StrategySettings для PRICE_ACTION не найдены (chatId=" + chatId + ")"
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

        safeLive(() -> live.pushSignal(chatId, StrategyType.PRICE_ACTION, symbol, null, Signal.hold(reason)));
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
