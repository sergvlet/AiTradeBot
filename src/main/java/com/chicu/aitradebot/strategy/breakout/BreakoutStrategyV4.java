package com.chicu.aitradebot.strategy.breakout;

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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@StrategyBinding(StrategyType.BREAKOUT)
@Slf4j
@Component
@RequiredArgsConstructor
public class BreakoutStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final long LOG_EVERY_TICKS = 250;
    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");

    private final StrategyLivePublisher live;
    private final BreakoutStrategySettingsService breakoutSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static class LocalState {
        Instant startedAt;
        boolean active;

        StrategySettings strategySettings;         // общие настройки
        BreakoutStrategySettings breakoutSettings; // узкие настройки

        String symbol;
        String exchange;
        NetworkType network;

        Instant lastSettingsLoadAt;
        Instant lastStrategyUpdatedAt;
        Instant lastBreakoutUpdatedAt;
        String lastSettingsFingerprint;

        // Диапазонный буфер
        Deque<BigDecimal> closes = new ArrayDeque<>();

        BigDecimal lastRangeHigh;
        BigDecimal lastRangeLow;

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

    // =========================
    // START / STOP
    // =========================

    @Override
    public void start(Long chatId, String ignored) {

        StrategySettings strategy = loadStrategySettings(chatId);
        BreakoutStrategySettings cfg = breakoutSettingsService.getOrCreate(chatId);

        LocalState st = new LocalState();
        st.active = true;
        st.startedAt = Instant.now();

        st.strategySettings = strategy;
        st.breakoutSettings = cfg;

        st.symbol = safeUpper(strategy.getSymbol());
        st.exchange = strategy.getExchangeName();
        st.network = strategy.getNetworkType();

        st.lastSettingsLoadAt = Instant.now();
        st.lastStrategyUpdatedAt = toInstant(strategy.getUpdatedAt());
        st.lastBreakoutUpdatedAt = cfg != null ? cfg.getUpdatedAt() : null;
        st.lastSettingsFingerprint = buildSettingsFingerprint(strategy, cfg);

        states.put(chatId, st);

        log.info("[BREAKOUT] ▶ START chatId={} symbol={} ex={} net={} lookback={} bufferPct={} minRangePct={}",
                chatId,
                st.symbol,
                st.exchange,
                st.network,
                cfg != null ? cfg.getRangeLookback() : null,
                cfg != null ? cfg.getBreakoutBufferPct() : null,
                cfg != null ? cfg.getMinRangePct() : null
        );

        safeLive(() -> live.pushState(chatId, StrategyType.BREAKOUT, st.symbol, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.BREAKOUT, st.symbol, null, Signal.hold("started")));
    }

    @Override
    public void stop(Long chatId, String ignored) {

        LocalState st = states.remove(chatId);
        if (st == null) return;

        String symbol = st.symbol;
        if (symbol != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.BREAKOUT, symbol));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.BREAKOUT, symbol));
            safeLive(() -> live.pushState(chatId, StrategyType.BREAKOUT, symbol, false));
        }

        log.info("[BREAKOUT] ⏹ STOP chatId={} symbol={} ticks={} warmups={} entries={} exits={} inPos={}",
                chatId, symbol, st.ticks, st.warmups, st.entries, st.exits, st.inPosition);
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

    // =========================
    // PRICE UPDATE
    // =========================

    @Override
    public void onPriceUpdate(Long chatId, String symbolFromTick, BigDecimal price, Instant ts) {

        LocalState st = states.get(chatId);
        if (st == null || !st.active) return;

        st.ticks++;

        if (price == null || price.signum() <= 0) {
            if (st.ticks % LOG_EVERY_TICKS == 0) {
                log.warn("[BREAKOUT] ⚠ Invalid price chatId={} price={}", chatId, price);
            }
            return;
        }

        Instant time = (ts != null) ? ts : Instant.now();

        // не мешаем тики разных символов
        String tickSymbol = safeUpper(symbolFromTick);
        String cfgSymbol = safeUpper(st.symbol);

        if (cfgSymbol != null && tickSymbol != null && !cfgSymbol.equals(tickSymbol)) {
            return;
        }
        if (cfgSymbol == null && tickSymbol != null) {
            st.symbol = tickSymbol;
            cfgSymbol = tickSymbol;
        }

        final String symbolForLive = safeUpper(st.symbol);
        safeLive(() -> live.pushPriceTick(chatId, StrategyType.BREAKOUT, symbolForLive, price, time));

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            StrategySettings strategy = st.strategySettings;
            BreakoutStrategySettings cfg = st.breakoutSettings;
            String symbol = safeUpper(st.symbol);

            if (cfg == null) {
                pushHoldThrottled(chatId, symbol, st, "no_breakout_settings", time);
                return;
            }

            Integer lookbackObj = cfg.getRangeLookback();
            int lookback = lookbackObj != null ? lookbackObj : 0;
            if (lookback < 10) {
                pushHoldThrottled(chatId, symbol, st, "rangeLookback<10", time);
                return;
            }

            // копим closes для диапазона
            st.closes.addLast(price);
            while (st.closes.size() > Math.max(lookback, lookback + 5)) {
                st.closes.removeFirst();
            }

            if (st.closes.size() < lookback) {
                st.warmups++;
                pushHoldThrottled(chatId, symbol, st, "warming_up", time);
                return;
            }

            // считаем high/low по окну lookback
            BigDecimal hi = null;
            BigDecimal lo = null;

            int i = 0;
            for (BigDecimal p : st.closes) {
                i++;
                if (i <= st.closes.size() - lookback) continue; // берём хвост lookback

                if (p == null) continue;
                hi = (hi == null) ? p : hi.max(p);
                lo = (lo == null) ? p : lo.min(p);
            }

            if (hi == null || lo == null || lo.signum() <= 0) {
                pushHoldThrottled(chatId, symbol, st, "range_invalid", time);
                return;
            }

            st.lastRangeHigh = hi;
            st.lastRangeLow = lo;

            double rangePct = hi.subtract(lo)
                    .divide(lo, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();

            Double minRangePct = cfg.getMinRangePct();
            if (minRangePct != null && rangePct < minRangePct) {
                pushHoldThrottled(chatId, symbol, st, "range_too_small", time);
                return;
            }

            // =====================================================
            // ENTRY (SPOT: только LONG)
            // =====================================================
            if (!st.inPosition) {

                // cooldown из StrategySettings (как у SCALPING)
                Integer cooldown = strategy != null ? strategy.getCooldownSeconds() : null;
                if (cooldown != null && cooldown > 0 && st.lastTradeClosedAt != null) {
                    long passed = Duration.between(st.lastTradeClosedAt, time).getSeconds();
                    if (passed < cooldown) {
                        pushHoldThrottled(chatId, symbol, st, "cooldown", time);
                        return;
                    }
                }

                // Условие пробоя:
                // price > high * (1 + bufferPct/100)
                double bufferPct = cfg.getBreakoutBufferPct() != null ? cfg.getBreakoutBufferPct() : 0.0;
                BigDecimal trigger = hi.multiply(BigDecimal.valueOf(1.0 + bufferPct / 100.0));

                if (price.compareTo(trigger) > 0) {

                    if (st.ticks % LOG_EVERY_TICKS == 0) {
                        log.info("[BREAKOUT] tick chatId={} symbol={} price={} hi={} lo={} rangePct={}",
                                chatId,
                                symbol,
                                price.stripTrailingZeros().toPlainString(),
                                hi.stripTrailingZeros().toPlainString(),
                                lo.stripTrailingZeros().toPlainString(),
                                String.format("%.3f", rangePct)
                        );
                    }

                    log.info("[BREAKOUT] ⚡ ENTRY try (SPOT LONG) chatId={} symbol={} price={} trigger={} rangePct={}",
                            chatId,
                            symbol,
                            price.stripTrailingZeros().toPlainString(),
                            trigger.stripTrailingZeros().toPlainString(),
                            String.format("%.3f", rangePct)
                    );

                    try {
                        // diff/score можно отдать как “сила пробоя” в долях
                        BigDecimal diffFrac = price.subtract(hi)
                                .divide(hi, 10, RoundingMode.HALF_UP);

                        var res = tradeExecutionService.executeEntry(
                                chatId,
                                StrategyType.BREAKOUT,
                                symbol,
                                price,
                                diffFrac,
                                time,
                                strategy
                        );

                        if (!res.executed()) {
                            log.info("[BREAKOUT] ✋ ENTRY blocked chatId={} reason={}", chatId, res.reason());
                            pushHoldThrottled(chatId, symbol, st, res.reason(), time);
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

                        log.info("[BREAKOUT] ✅ ENTRY OK chatId={} qty={} entry={} tp={} sl={} orderId={}",
                                chatId,
                                st.entryQty != null ? st.entryQty.stripTrailingZeros().toPlainString() : "null",
                                st.entryPrice != null ? st.entryPrice.stripTrailingZeros().toPlainString() : "null",
                                st.tp != null ? st.tp.stripTrailingZeros().toPlainString() : "null",
                                st.sl != null ? st.sl.stripTrailingZeros().toPlainString() : "null",
                                st.entryOrderId
                        );

                        double score = diffFrac.doubleValue();
                        safeLive(() -> live.pushSignal(chatId, StrategyType.BREAKOUT, symbol, null,
                                Signal.buy(score, "breakout")));


                        // после входа можно очистить окно, чтобы не ловить повторный “тот же” пробой
                        st.closes.clear();
                        st.lastHoldReason = null;

                    } catch (Exception e) {
                        log.error("[BREAKOUT] ❌ ENTRY failed chatId={} err={}", chatId, e.getMessage(), e);
                        pushHoldThrottled(chatId, symbol, st, "entry_failed", time);
                    }
                } else {
                    pushHoldThrottled(chatId, symbol, st, "no_breakout", time);
                }
            }

            // =====================================================
            // EXIT
            // =====================================================
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {
                try {
                    var ex = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.BREAKOUT,
                            symbol,
                            price,
                            time,
                            st.isLong,
                            st.entryQty,
                            st.tp,
                            st.sl
                    );

                    if (ex.executed()) {
                        st.exits++;

                        log.info("[BREAKOUT] ✅ EXIT OK chatId={} price={} (tp={} sl={})",
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

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.BREAKOUT, symbol));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.BREAKOUT, symbol));

                        safeLive(() -> live.pushSignal(chatId, StrategyType.BREAKOUT, symbol, null,
                                Signal.sell(1.0, "exit")));

                    }

                } catch (Exception e) {
                    log.error("[BREAKOUT] ❌ EXIT failed chatId={} err={}", chatId, e.getMessage(), e);
                }
            }
        }
    }

    // =========================
    // SETTINGS REFRESH (как у тебя)
    // =========================

    private void refreshSettingsIfNeeded(Long chatId, LocalState st, Instant now) {

        if (st.lastSettingsLoadAt != null &&
            Duration.between(st.lastSettingsLoadAt, now).compareTo(SETTINGS_REFRESH_EVERY) < 0) {
            return;
        }

        try {
            StrategySettings loaded = loadStrategySettings(chatId);
            BreakoutStrategySettings cfg = breakoutSettingsService.getOrCreate(chatId);

            Instant loadedUpd = loaded != null ? toInstant(loaded.getUpdatedAt()) : null;
            Instant cfgUpd = cfg != null ? cfg.getUpdatedAt() : null;

            String fp = buildSettingsFingerprint(loaded, cfg);

            boolean changed =
                    st.lastSettingsFingerprint == null ||
                    !st.lastSettingsFingerprint.equals(fp) ||
                    !Objects.equals(st.lastStrategyUpdatedAt, loadedUpd) ||
                    !Objects.equals(st.lastBreakoutUpdatedAt, cfgUpd);

            String oldSymbol = safeUpper(st.symbol);

            if (loaded != null) st.strategySettings = loaded;
            if (cfg != null) st.breakoutSettings = cfg;

            if (loaded != null) {
                String loadedSymbol = safeUpper(loaded.getSymbol());
                if (loadedSymbol != null) st.symbol = loadedSymbol;

                if (loaded.getExchangeName() != null) st.exchange = loaded.getExchangeName();
                if (loaded.getNetworkType() != null) st.network = loaded.getNetworkType();
            }

            st.lastSettingsLoadAt = now;

            if (changed) {
                st.lastSettingsFingerprint = fp;
                st.lastStrategyUpdatedAt = loadedUpd;
                st.lastBreakoutUpdatedAt = cfgUpd;

                log.info("[BREAKOUT] ⚙️ settings updated chatId={} symbol={} ex={} net={} lookback={} bufferPct={} minRangePct={}",
                        chatId,
                        st.symbol,
                        st.exchange,
                        st.network,
                        cfg != null ? cfg.getRangeLookback() : null,
                        cfg != null ? cfg.getBreakoutBufferPct() : null,
                        cfg != null ? cfg.getMinRangePct() : null
                );

                String newSymbol = safeUpper(st.symbol);
                if (oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol)) {
                    st.closes.clear();
                    st.lastRangeHigh = null;
                    st.lastRangeLow = null;
                    st.lastHoldReason = null;
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[BREAKOUT] ⚠️ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    // =========================
    // LOAD StrategySettings (как у тебя)
    // =========================

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId, null, null)
                .stream()
                .filter(s -> s.getType() == StrategyType.BREAKOUT)
                .sorted(
                        Comparator
                                .comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                                .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                )
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "StrategySettings для BREAKOUT не найдены (chatId=" + chatId + ")"
                ));
    }

    // =========================
    // Fingerprint (как у тебя)
    // =========================

    private String buildSettingsFingerprint(StrategySettings ss, BreakoutStrategySettings cfg) {
        String symbol = ss != null ? safeUpper(ss.getSymbol()) : "null";
        String ex     = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String net    = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String tf     = ss != null ? safe(ss.getTimeframe()) : "null";

        String candles = ss != null && ss.getCachedCandlesLimit() != null
                ? String.valueOf(ss.getCachedCandlesLimit())
                : "null";

        String cooldown = ss != null && ss.getCooldownSeconds() != null
                ? String.valueOf(ss.getCooldownSeconds())
                : "null";

        String lookback = cfg != null ? String.valueOf(cfg.getRangeLookback()) : "null";
        String buffer   = cfg != null ? String.valueOf(cfg.getBreakoutBufferPct()) : "null";
        String minRange = cfg != null ? String.valueOf(cfg.getMinRangePct()) : "null";

        return symbol + "|" + ex + "|" + net + "|" + tf
               + "|" + candles + "|" + cooldown
               + "|" + lookback + "|" + buffer + "|" + minRange;
    }

    // =========================
    // LIVE HELPERS
    // =========================

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

        safeLive(() -> live.pushSignal(chatId, StrategyType.BREAKOUT, symbol, null, Signal.hold(reason)));
    }

    // =========================
    // UTILS
    // =========================

    private static String safe(String s) {
        return s == null ? "null" : s.trim();
    }

    private static String safeUpper(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t.toUpperCase();
    }

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt == null ? null : ldt.atZone(ZONE).toInstant();
    }
}
