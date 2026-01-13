package com.chicu.aitradebot.strategy.momentum;

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

@StrategyBinding(StrategyType.MOMENTUM)
@Slf4j
@Component
@RequiredArgsConstructor
public class MomentumStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final long LOG_EVERY_TICKS = 250;
    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");

    private final StrategyLivePublisher live;
    private final MomentumStrategySettingsService momentumSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static class LocalState {
        Instant startedAt;
        boolean active;

        StrategySettings strategySettings;
        MomentumStrategySettings momentumSettings;

        String symbol;
        String exchange;
        NetworkType network;

        Instant lastSettingsLoadAt;
        Instant lastStrategyUpdatedAt;
        Instant lastMomentumUpdatedAt;
        String lastFingerprint;

        Deque<BigDecimal> window = new ArrayDeque<>();

        boolean inPosition;
        boolean isLong;

        BigDecimal entryPrice;
        BigDecimal tp;
        BigDecimal sl;

        Long entryOrderId;
        BigDecimal entryQty;

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

        StrategySettings strategy = loadStrategySettings(chatId);
        MomentumStrategySettings cfg = momentumSettingsService.getOrCreate(chatId);

        LocalState st = new LocalState();
        st.active = true;
        st.startedAt = Instant.now();

        st.strategySettings = strategy;
        st.momentumSettings = cfg;

        st.symbol = safeUpper(strategy.getSymbol());
        st.exchange = strategy.getExchangeName();
        st.network = strategy.getNetworkType();

        st.lastSettingsLoadAt = Instant.now();
        st.lastStrategyUpdatedAt = toInstant(strategy.getUpdatedAt());
        st.lastMomentumUpdatedAt = cfg != null ? cfg.getUpdatedAt() : null;
        st.lastFingerprint = buildFingerprint(strategy, cfg);

        states.put(chatId, st);

        log.info("[MOMENTUM] ▶ START chatId={} symbol={} ex={} net={} lookback={} minChangePct={}",
                chatId, st.symbol, st.exchange, st.network,
                cfg != null ? cfg.getLookbackBars() : null,
                cfg != null ? cfg.getMinPriceChangePct() : null
        );

        safeLive(() -> live.pushState(chatId, StrategyType.MOMENTUM, st.symbol, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.MOMENTUM, st.symbol, null, Signal.hold("started")));
    }

    @Override
    public void stop(Long chatId, String ignored) {

        LocalState st = states.remove(chatId);
        if (st == null) return;

        String symbol = st.symbol;

        if (symbol != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.MOMENTUM, symbol));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.MOMENTUM, symbol));
            safeLive(() -> live.pushState(chatId, StrategyType.MOMENTUM, symbol, false));
        }

        log.info("[MOMENTUM] ⏹ STOP chatId={} symbol={} ticks={} warmups={} entries={} exits={} inPos={}",
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
                log.warn("[MOMENTUM] ⚠ Invalid price chatId={} price={}", chatId, price);
            }
            return;
        }

        Instant time = (ts != null) ? ts : Instant.now();

        // не мешаем символы
        String tickSymbol = safeUpper(symbolFromTick);
        String cfgSymbol = safeUpper(st.symbol);

        if (cfgSymbol != null && tickSymbol != null && !cfgSymbol.equals(tickSymbol)) return;
        if (cfgSymbol == null && tickSymbol != null) {
            st.symbol = tickSymbol;
            cfgSymbol = tickSymbol;
        }

        final String symbolForLive = safeUpper(st.symbol);
        safeLive(() -> live.pushPriceTick(chatId, StrategyType.MOMENTUM, symbolForLive, price, time));

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            StrategySettings strategy = st.strategySettings;
            MomentumStrategySettings cfg = st.momentumSettings;
            String symbol = safeUpper(st.symbol);

            if (cfg == null) {
                pushHoldThrottled(chatId, symbol, st, 0.0, "no_momentum_settings", time);
                return;
            }

            int lookback = cfg.getLookbackBars() != null ? cfg.getLookbackBars() : 0;
            if (lookback < 2) {
                pushHoldThrottled(chatId, symbol, st, 0.0, "lookback<2", time);
                return;
            }

            st.window.addLast(price);
            while (st.window.size() > lookback) st.window.removeFirst();

            if (st.window.size() < lookback) {
                st.warmups++;
                pushHoldThrottled(chatId, symbol, st, 0.0, "warming_up", time);
                return;
            }

            BigDecimal first = st.window.getFirst();
            BigDecimal last = st.window.getLast();

            if (first == null || first.signum() <= 0) {
                pushHoldThrottled(chatId, symbol, st, 0.0, "first_invalid", time);
                return;
            }

            // changePct = (last-first)/first * 100
            double changePct = last.subtract(first)
                    .divide(first, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();

            double minChangePct = cfg.getMinPriceChangePct() != null ? cfg.getMinPriceChangePct() : 0.0;

            if (st.ticks % LOG_EVERY_TICKS == 0) {
                log.info("[MOMENTUM] tick chatId={} symbol={} price={} changePct={} minChangePct={} inPos={}",
                        chatId,
                        symbol,
                        price.stripTrailingZeros().toPlainString(),
                        String.format("%.4f", changePct),
                        String.format("%.4f", minChangePct),
                        st.inPosition
                );
            }

            // =====================================================
            // ENTRY (SPOT LONG)
            // =====================================================
            if (!st.inPosition && changePct >= minChangePct) {

                Integer cooldown = strategy != null ? strategy.getCooldownSeconds() : null;
                if (cooldown != null && cooldown > 0 && st.lastTradeClosedAt != null) {
                    long passed = Duration.between(st.lastTradeClosedAt, time).getSeconds();
                    if (passed < cooldown) {
                        pushHoldThrottled(chatId, symbol, st, changePct, "cooldown", time);
                        return;
                    }
                }

                log.info("[MOMENTUM] ⚡ ENTRY try chatId={} symbol={} price={} changePct={}%",
                        chatId,
                        symbol,
                        price.stripTrailingZeros().toPlainString(),
                        String.format("%.4f", changePct)
                );

                try {
                    BigDecimal score = BigDecimal.valueOf(changePct).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);

                    var res = tradeExecutionService.executeEntry(
                            chatId,
                            StrategyType.MOMENTUM,
                            symbol,
                            price,
                            score,
                            time,
                            strategy
                    );

                    if (!res.executed()) {
                        log.info("[MOMENTUM] ✋ ENTRY blocked chatId={} reason={}", chatId, res.reason());
                        pushHoldThrottled(chatId, symbol, st, changePct, res.reason(), time);
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

                    log.info("[MOMENTUM] ✅ ENTRY OK chatId={} qty={} entry={} tp={} sl={} orderId={}",
                            chatId,
                            st.entryQty != null ? st.entryQty.stripTrailingZeros().toPlainString() : "null",
                            st.entryPrice != null ? st.entryPrice.stripTrailingZeros().toPlainString() : "null",
                            st.tp != null ? st.tp.stripTrailingZeros().toPlainString() : "null",
                            st.sl != null ? st.sl.stripTrailingZeros().toPlainString() : "null",
                            st.entryOrderId
                    );

                    safeLive(() -> live.pushSignal(chatId, StrategyType.MOMENTUM, symbol, null,
                            Signal.buy(changePct, "momentum")));

                    st.window.clear();
                    st.lastHoldReason = null;

                } catch (Exception e) {
                    log.error("[MOMENTUM] ❌ ENTRY failed chatId={} err={}", chatId, e.getMessage(), e);
                    pushHoldThrottled(chatId, symbol, st, changePct, "entry_failed", time);
                }
            } else if (!st.inPosition) {
                pushHoldThrottled(chatId, symbol, st, changePct, "no_momentum", time);
            }

            // =====================================================
            // EXIT
            // =====================================================
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {
                try {
                    var ex = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.MOMENTUM,
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

                        log.info("[MOMENTUM] ✅ EXIT OK chatId={} price={} (tp={} sl={})",
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

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.MOMENTUM, symbol));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.MOMENTUM, symbol));

                        safeLive(() -> live.pushSignal(chatId, StrategyType.MOMENTUM, symbol, null,
                                Signal.sell(1.0, "exit")));
                    }

                } catch (Exception e) {
                    log.error("[MOMENTUM] ❌ EXIT failed chatId={} err={}", chatId, e.getMessage(), e);
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
            MomentumStrategySettings cfg = momentumSettingsService.getOrCreate(chatId);

            Instant loadedUpd = loaded != null ? toInstant(loaded.getUpdatedAt()) : null;
            Instant cfgUpd = cfg != null ? cfg.getUpdatedAt() : null;

            String fp = buildFingerprint(loaded, cfg);

            boolean changed =
                    st.lastFingerprint == null ||
                    !st.lastFingerprint.equals(fp) ||
                    !Objects.equals(st.lastStrategyUpdatedAt, loadedUpd) ||
                    !Objects.equals(st.lastMomentumUpdatedAt, cfgUpd);

            String oldSymbol = safeUpper(st.symbol);

            if (loaded != null) st.strategySettings = loaded;
            if (cfg != null) st.momentumSettings = cfg;

            if (loaded != null) {
                String loadedSymbol = safeUpper(loaded.getSymbol());
                if (loadedSymbol != null) st.symbol = loadedSymbol;

                if (loaded.getExchangeName() != null) st.exchange = loaded.getExchangeName();
                if (loaded.getNetworkType() != null) st.network = loaded.getNetworkType();
            }

            st.lastSettingsLoadAt = now;

            if (changed) {
                st.lastFingerprint = fp;
                st.lastStrategyUpdatedAt = loadedUpd;
                st.lastMomentumUpdatedAt = cfgUpd;

                log.info("[MOMENTUM] ⚙️ settings updated chatId={} symbol={} ex={} net={} lookback={} minChangePct={}",
                        chatId,
                        st.symbol,
                        st.exchange,
                        st.network,
                        cfg != null ? cfg.getLookbackBars() : null,
                        cfg != null ? cfg.getMinPriceChangePct() : null
                );

                String newSymbol = safeUpper(st.symbol);
                if (oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol)) {
                    st.window.clear();
                    st.lastHoldReason = null;
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[MOMENTUM] ⚠️ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    private String buildFingerprint(StrategySettings ss, MomentumStrategySettings cfg) {
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

        String lookback = cfg != null ? String.valueOf(cfg.getLookbackBars()) : "null";
        String minChg   = cfg != null ? String.valueOf(cfg.getMinPriceChangePct()) : "null";
        String confirm  = cfg != null ? String.valueOf(cfg.getConfirmBars()) : "null";

        return symbol + "|" + ex + "|" + net + "|" + tf + "|" + candles + "|" + cooldown
                + "|" + lookback + "|" + minChg + "|" + confirm;
    }

    // =====================================================
    // LOAD StrategySettings
    // =====================================================

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId, null, null)
                .stream()
                .filter(s -> s.getType() == StrategyType.MOMENTUM)
                .sorted(
                        Comparator
                                .comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                                .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                )
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "StrategySettings для MOMENTUM не найдены (chatId=" + chatId + ")"
                ));
    }

    // =====================================================
    // LIVE HELPERS
    // =====================================================

    private void safeLive(Runnable r) {
        try { r.run(); } catch (Exception ignored) {}
    }

    private void pushHoldThrottled(Long chatId, String symbol, LocalState st, double score, String reason, Instant now) {
        if (symbol == null) return;

        if (Objects.equals(st.lastHoldReason, reason) && st.lastHoldAt != null) {
            long ms = Duration.between(st.lastHoldAt, now).toMillis();
            if (ms < 2000) return;
        }

        st.lastHoldReason = reason;
        st.lastHoldAt = now;

        safeLive(() -> live.pushSignal(chatId, StrategyType.MOMENTUM, symbol, null, Signal.hold(reason)));
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

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt == null ? null : ldt.atZone(ZONE).toInstant();
    }
}
