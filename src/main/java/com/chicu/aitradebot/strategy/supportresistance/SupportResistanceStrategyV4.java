package com.chicu.aitradebot.strategy.supportresistance;

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

@StrategyBinding(StrategyType.SUPPORT_RESISTANCE)
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportResistanceStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final long LOG_EVERY_TICKS = 250;

    private final StrategyLivePublisher live;
    private final SupportResistanceStrategySettingsService srSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        SupportResistanceStrategySettings cfg;

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
        SupportResistanceStrategySettings cfg = srSettingsService.getOrCreate(chatId);

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

        log.info("[SR] ▶ START chatId={} symbol={} window={} minRangePct={} bounce={} breakout={}",
                chatId,
                st.symbol,
                cfg.getWindowSize(),
                cfg.getMinRangePct(),
                cfg.isEnabledBounce(),
                cfg.isEnabledBreakout()
        );

        final String sym = st.symbol; // ✅ для лямбд
        safeLive(() -> live.pushState(chatId, StrategyType.SUPPORT_RESISTANCE, sym, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.SUPPORT_RESISTANCE, sym, null, Signal.hold("started")));
    }

    @Override
    public void stop(Long chatId, String ignored) {

        LocalState st = states.remove(chatId);
        if (st == null) return;

        final String sym = st.symbol;

        if (sym != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.SUPPORT_RESISTANCE, sym));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.SUPPORT_RESISTANCE, sym));
            safeLive(() -> live.pushState(chatId, StrategyType.SUPPORT_RESISTANCE, sym, false));
        }

        log.info("[SR] ⏹ STOP chatId={} symbol={} ticks={} warmups={} entries={} exits={} inPos={}",
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
                log.warn("[SR] ⚠ Invalid price chatId={} price={}", chatId, price);
            }
            return;
        }

        Instant time = ts != null ? ts : Instant.now();

        // не мешаем символы
        String tickSymbol = safeUpper(symbolFromTick);
        String cfgSymbol = safeUpper(st.symbol);
        if (cfgSymbol != null && tickSymbol != null && !cfgSymbol.equals(tickSymbol)) return;
        if (cfgSymbol == null && tickSymbol != null) st.symbol = tickSymbol;

        final String symbolForLive = safeUpper(st.symbol);
        safeLive(() -> live.pushPriceTick(chatId, StrategyType.SUPPORT_RESISTANCE, symbolForLive, price, time));

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            StrategySettings ss = st.ss;
            SupportResistanceStrategySettings cfg = st.cfg;

            final String sym = safeUpper(st.symbol);

            if (cfg == null) {
                pushHoldThrottled(chatId, sym, st, "no_sr_settings", time);
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
                pushHoldThrottled(chatId, sym, st, "sr_invalid_window", time);
                return;
            }

            BigDecimal range = high.subtract(low);
            if (range.signum() <= 0) {
                pushHoldThrottled(chatId, sym, st, "sr_range_zero", time);
                return;
            }

            double rangePct = range
                    .divide(low, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();

            double minRangePct = cfg.getMinRangePct() != null ? cfg.getMinRangePct() : 0.0;
            if (rangePct < minRangePct) {
                if (st.ticks % LOG_EVERY_TICKS == 0) {
                    log.info("[SR] range too small chatId={} symbol={} rangePct={} minRangePct={}",
                            chatId, sym, fmt(rangePct), fmt(minRangePct));
                }
                pushHoldThrottled(chatId, sym, st, "range_too_small", time);
                return;
            }

            // near support?
            double entryFromSupportPct = cfg.getEntryFromSupportPct() != null ? cfg.getEntryFromSupportPct() : 0.0;
            BigDecimal supportBand = low
                    .multiply(BigDecimal.valueOf(entryFromSupportPct))
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);

            boolean nearSupport = price.compareTo(low.add(supportBand)) <= 0;

            // breakout above resistance?
            double breakoutPct = cfg.getBreakoutAboveResistancePct() != null ? cfg.getBreakoutAboveResistancePct() : 0.0;
            BigDecimal breakoutBand = high
                    .multiply(BigDecimal.valueOf(breakoutPct))
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);

            boolean breakout = price.compareTo(high.add(breakoutBand)) >= 0;

            if (st.ticks % LOG_EVERY_TICKS == 0) {
                log.info("[SR] tick chatId={} symbol={} price={} support={} resistance={} rangePct={} nearSupport={} breakout={}",
                        chatId,
                        sym,
                        price.stripTrailingZeros().toPlainString(),
                        low.stripTrailingZeros().toPlainString(),
                        high.stripTrailingZeros().toPlainString(),
                        fmt(rangePct),
                        nearSupport,
                        breakout
                );
            }

            // =====================================================
            // ENTRY (SPOT LONG)
            // =====================================================
            if (!st.inPosition) {

                Integer cooldown = ss != null ? ss.getCooldownSeconds() : null;
                if (cooldown != null && cooldown > 0 && st.lastTradeClosedAt != null) {
                    long passed = Duration.between(st.lastTradeClosedAt, time).getSeconds();
                    if (passed < cooldown) {
                        pushHoldThrottled(chatId, sym, st, "cooldown", time);
                        return;
                    }
                }

                boolean doBounce = cfg.isEnabledBounce() && nearSupport;
                boolean doBreakout = cfg.isEnabledBreakout() && breakout;

                if (doBounce || doBreakout) {

                    String reason = doBreakout ? "breakout_above_resistance" : "bounce_from_support";

                    // score 0..100: чем ближе к low (для bounce) тем выше, для breakout фикс
                    double score;
                    if (doBreakout) {
                        score = 85.0;
                    } else {
                        // distance from low in % of range
                        double pos = price.subtract(low).divide(range, 10, RoundingMode.HALF_UP).doubleValue();
                        score = clamp01(1.0 - pos) * 100.0;
                    }

                    log.info("[SR] ⚡ ENTRY try chatId={} symbol={} price={} reason={} score={}",
                            chatId, sym, price.stripTrailingZeros().toPlainString(), reason, fmt(score));

                    try {
                        var res = tradeExecutionService.executeEntry(
                                chatId,
                                StrategyType.SUPPORT_RESISTANCE,
                                sym,
                                price,
                                BigDecimal.valueOf(score / 100.0), // 0..1
                                time,
                                ss
                        );

                        if (!res.executed()) {
                            log.info("[SR] ✋ ENTRY blocked chatId={} reason={}", chatId, res.reason());
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

                        safeLive(() -> live.pushSignal(chatId, StrategyType.SUPPORT_RESISTANCE, sym, null,
                                Signal.buy(score, reason)));

                        st.window.clear();
                        st.lastHoldReason = null;

                    } catch (Exception e) {
                        log.error("[SR] ❌ ENTRY failed chatId={} err={}", chatId, e.getMessage(), e);
                        pushHoldThrottled(chatId, sym, st, "entry_failed", time);
                    }

                    return;
                }

                pushHoldThrottled(chatId, sym, st, "no_entry", time);
            }

            // =====================================================
            // EXIT: TP/SL через TradeExecutionService
            // =====================================================
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {
                try {
                    var ex = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.SUPPORT_RESISTANCE,
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

                        log.info("[SR] ✅ EXIT OK chatId={} price={} (tp={} sl={})",
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

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.SUPPORT_RESISTANCE, sym));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.SUPPORT_RESISTANCE, sym));

                        safeLive(() -> live.pushSignal(chatId, StrategyType.SUPPORT_RESISTANCE, sym, null,
                                Signal.sell(1.0, "tp_sl_exit")));
                    }

                } catch (Exception e) {
                    log.error("[SR] ❌ EXIT failed chatId={} err={}", chatId, e.getMessage(), e);
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
            SupportResistanceStrategySettings cfg = srSettingsService.getOrCreate(chatId);

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

                log.info("[SR] ⚙️ settings updated chatId={} symbol={} window={} minRangePct={} bounce={} breakout={}",
                        chatId,
                        st.symbol,
                        cfg != null ? cfg.getWindowSize() : null,
                        cfg != null ? cfg.getMinRangePct() : null,
                        cfg != null && cfg.isEnabledBounce(),
                        cfg != null && cfg.isEnabledBreakout()
                );

                String newSymbol = safeUpper(st.symbol);
                if (oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol)) {
                    st.window.clear();
                    st.lastHoldReason = null;
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[SR] ⚠️ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    private String buildFingerprint(StrategySettings ss, SupportResistanceStrategySettings cfg) {
        String symbol = ss != null ? safeUpper(ss.getSymbol()) : "null";
        String ex     = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String net    = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String tf     = ss != null ? safe(ss.getTimeframe()) : "null";
        String candles = ss != null && ss.getCachedCandlesLimit() != null ? String.valueOf(ss.getCachedCandlesLimit()) : "null";
        String cooldown = ss != null && ss.getCooldownSeconds() != null ? String.valueOf(ss.getCooldownSeconds()) : "null";

        String w = cfg != null ? String.valueOf(cfg.getWindowSize()) : "null";
        String minR = cfg != null ? String.valueOf(cfg.getMinRangePct()) : "null";
        String entry = cfg != null ? String.valueOf(cfg.getEntryFromSupportPct()) : "null";
        String br = cfg != null ? String.valueOf(cfg.getBreakoutAboveResistancePct()) : "null";
        String b1 = cfg != null ? String.valueOf(cfg.isEnabledBounce()) : "null";
        String b2 = cfg != null ? String.valueOf(cfg.isEnabledBreakout()) : "null";

        return symbol + "|" + ex + "|" + net + "|" + tf + "|" + candles + "|" + cooldown
                + "|" + w + "|" + minR + "|" + entry + "|" + br + "|" + b1 + "|" + b2;
    }

    // =====================================================
    // LOAD StrategySettings
    // =====================================================

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId, null, null)
                .stream()
                .filter(s -> s.getType() == StrategyType.SUPPORT_RESISTANCE)
                .sorted(
                        Comparator
                                .comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                                .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                )
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "StrategySettings для SUPPORT_RESISTANCE не найдены (chatId=" + chatId + ")"
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

        safeLive(() -> live.pushSignal(chatId, StrategyType.SUPPORT_RESISTANCE, symbol, null, Signal.hold(reason)));
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
