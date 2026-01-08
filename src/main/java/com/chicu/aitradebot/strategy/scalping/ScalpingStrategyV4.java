package com.chicu.aitradebot.strategy.scalping;

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

@StrategyBinding(StrategyType.SCALPING)
@Slf4j
@Component
@RequiredArgsConstructor
public class ScalpingStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final long LOG_EVERY_TICKS = 200;
    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");

    private final StrategyLivePublisher live;
    private final ScalpingStrategySettingsService scalpingSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    /**
     * Важно: threshold в настройках хранится как ДОЛЯ (fraction):
     * 0.005 = 0.5% (а не 0.005%)
     */
    public record WindowZoneSnapshot(BigDecimal high, BigDecimal low) {}

    public WindowZoneSnapshot getLastWindowZone(long chatId) {
        LocalState st = states.get(chatId);
        if (st == null) return null;
        if (st.lastWindowHigh == null || st.lastWindowLow == null) return null;
        return new WindowZoneSnapshot(st.lastWindowHigh, st.lastWindowLow);
    }

    private static class LocalState {
        Instant startedAt;
        boolean active;

        String lastSettingsFingerprint;
        Instant lastSettingsUpdatedAt;   // StrategySettings.updatedAt (LocalDateTime -> Instant)
        Instant lastScalpingUpdatedAt;   // ScalpingStrategySettings.updatedAt (Instant)

        StrategySettings strategySettings;
        ScalpingStrategySettings scalpingSettings;

        String symbol;
        String exchange;
        NetworkType network;

        Instant lastSettingsLoadAt;

        Deque<BigDecimal> window = new ArrayDeque<>();

        boolean inPosition;
        boolean isLong;

        BigDecimal entryPrice;
        BigDecimal tp;
        BigDecimal sl;

        Long entryOrderId;
        BigDecimal entryQty;
        String entrySide;

        Instant lastTradeClosedAt;

        BigDecimal lastWindowHigh;
        BigDecimal lastWindowLow;

        long ticks;
        long warmups;
        long entries;
        long exits;

        // чтобы не спамить одинаковыми hold-сигналами
        String lastHoldReason;
        Instant lastHoldAt;
    }

    // =====================================================
    // START / STOP
    // =====================================================
    @Override
    public void start(Long chatId, String ignored) {

        StrategySettings strategy = loadStrategySettings(chatId);
        ScalpingStrategySettings cfg = scalpingSettingsService.getOrCreate(chatId);

        LocalState st = new LocalState();
        st.active = true;
        st.startedAt = Instant.now();

        st.strategySettings = strategy;
        st.scalpingSettings = cfg;

        st.symbol = safeUpper(strategy.getSymbol());
        st.exchange = strategy.getExchangeName();
        st.network = strategy.getNetworkType();

        st.lastSettingsLoadAt = Instant.now();

        st.lastWindowHigh = null;
        st.lastWindowLow = null;

        st.lastSettingsUpdatedAt = toInstant(strategy.getUpdatedAt());
        st.lastScalpingUpdatedAt = (cfg != null) ? cfg.getUpdatedAt() : null;
        st.lastSettingsFingerprint = buildSettingsFingerprint(strategy, cfg);

        states.put(chatId, st);

        log.info("[SCALPING] ▶ START chatId={} symbol={} ex={} net={} windowSize={} thr={} cooldownSec={}",
                chatId,
                st.symbol,
                st.exchange,
                st.network,
                cfg != null ? cfg.getWindowSize() : null,
                cfg != null ? num(cfg.getPriceChangeThreshold()) : null,
                strategy.getCooldownSeconds()
        );

        safeLive(() -> live.pushState(chatId, StrategyType.SCALPING, st.symbol, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.SCALPING, st.symbol, null, Signal.hold("started")));
    }

    @Override
    public void stop(Long chatId, String ignored) {

        LocalState st = states.remove(chatId);
        if (st == null) return;

        String symbol = st.symbol;

        if (symbol != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.SCALPING, symbol));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.SCALPING, symbol));
            safeLive(() -> live.clearWindowZone(chatId, StrategyType.SCALPING, symbol));
            safeLive(() -> live.pushState(chatId, StrategyType.SCALPING, symbol, false));
        }

        log.info("[SCALPING] ⏹ STOP chatId={} symbol={} ticks={} warmups={} entries={} exits={} inPos={}",
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
                log.warn("[SCALPING] ⚠ Invalid price chatId={} price={}", chatId, price);
            }
            return;
        }

        Instant time = (ts != null) ? ts : Instant.now();

        // 🔥 важный фикс: не мешаем цены разных символов
        String tickSymbol = safeUpper(symbolFromTick);
        String cfgSymbol = safeUpper(st.symbol);

        if (cfgSymbol != null && tickSymbol != null && !cfgSymbol.equals(tickSymbol)) {
            return; // игнорим чужие тики
        }
        if (cfgSymbol == null && tickSymbol != null) {
            st.symbol = tickSymbol;
            cfgSymbol = tickSymbol;
        }

        // лайв тик (не ломаем торговлю если упадёт)
        final String symbolForLive = safeUpper(st.symbol);
        safeLive(() -> live.pushPriceTick(chatId, StrategyType.SCALPING, symbolForLive, price, time));

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            StrategySettings strategy = st.strategySettings;
            ScalpingStrategySettings cfg = st.scalpingSettings;
            String symbol = safeUpper(st.symbol);

            if (cfg == null) {
                pushHoldThrottled(chatId, symbol, st, "no_scalping_settings", time);
                return;
            }

            int windowSize = cfg.getWindowSize() != null ? cfg.getWindowSize() : 0;
            if (windowSize <= 1) {
                pushHoldThrottled(chatId, symbol, st, "windowSize<=1", time);
                return;
            }

            st.window.addLast(price);
            while (st.window.size() > windowSize) st.window.removeFirst();

            if (st.window.size() < windowSize) {
                st.warmups++;
                if (st.ticks % LOG_EVERY_TICKS == 0) {
                    log.info("[SCALPING] … warming chatId={} symbol={} window={}/{} inPos={}",
                            chatId, symbol, st.window.size(), windowSize, st.inPosition);
                }
                pushHoldThrottled(chatId, symbol, st, "warming_up", time);
                return;
            }

            BigDecimal first = st.window.getFirst();
            BigDecimal last = st.window.getLast();

            if (first == null || first.signum() <= 0) {
                pushHoldThrottled(chatId, symbol, st, "first_price_invalid", time);
                return;
            }

            // diffFrac (доля): (last-first)/first
            double diffFrac = last.subtract(first)
                    .divide(first, 10, RoundingMode.HALF_UP)
                    .doubleValue();

            Double thresholdObj = cfg.getPriceChangeThreshold();
            double thresholdFrac = (thresholdObj != null) ? thresholdObj : 0.0;

            if (st.ticks % LOG_EVERY_TICKS == 0) {
                log.info("[SCALPING] tick chatId={} symbol={} price={} diff={} thr={} windowSize={} inPos={}",
                        chatId,
                        symbol,
                        price.stripTrailingZeros().toPlainString(),
                        String.format("%.6f", diffFrac),
                        String.format("%.6f", thresholdFrac),
                        windowSize,
                        st.inPosition
                );
            }

            // =====================================================
            // WINDOW ZONE (рисуем)
            // thresholdFrac: 0.005 => +-0.5%
            // =====================================================
            if (thresholdFrac <= 0) {
                if (st.lastWindowHigh != null || st.lastWindowLow != null) {
                    st.lastWindowHigh = null;
                    st.lastWindowLow = null;
                    safeLive(() -> live.clearWindowZone(chatId, StrategyType.SCALPING, symbol));
                }
            } else {
                BigDecimal high = last.multiply(BigDecimal.valueOf(1.0 + thresholdFrac));
                BigDecimal low  = last.multiply(BigDecimal.valueOf(1.0 - thresholdFrac));

                st.lastWindowHigh = high;
                st.lastWindowLow = low;

                safeLive(() -> live.pushWindowZone(chatId, StrategyType.SCALPING, symbol, high, low));
            }

            // =====================================================
// ENTRY (SPOT: только LONG)
// =====================================================
            if (!st.inPosition && thresholdFrac > 0 && diffFrac >= thresholdFrac) { // ✅ только рост

                Integer cooldown = strategy != null ? strategy.getCooldownSeconds() : null;
                if (cooldown != null && cooldown > 0 && st.lastTradeClosedAt != null) {
                    long passed = Duration.between(st.lastTradeClosedAt, time).getSeconds();
                    if (passed < cooldown) {
                        pushHoldThrottled(chatId, symbol, st, "cooldown", time);
                        return;
                    }
                }

                log.info("[SCALPING] ⚡ ENTRY try (SPOT LONG) chatId={} symbol={} price={} diff={} thr={}",
                        chatId,
                        symbol,
                        price.stripTrailingZeros().toPlainString(),
                        String.format("%.6f", diffFrac),
                        String.format("%.6f", thresholdFrac)
                );

                try {
                    var res = tradeExecutionService.executeEntry(
                            chatId,
                            StrategyType.SCALPING,
                            symbol,
                            price,
                            BigDecimal.valueOf(diffFrac), // diffFrac >= thresholdFrac
                            time,
                            strategy
                    );

                    if (!res.executed()) {
                        log.info("[SCALPING] ✋ ENTRY blocked chatId={} reason={}", chatId, res.reason());
                        pushHoldThrottled(chatId, symbol, st, res.reason(), time);
                        return;
                    }

                    st.entries++;
                    st.inPosition = true;
                    st.isLong = true;               // ✅ SPOT
                    st.entryPrice = res.entryPrice();
                    st.tp = res.tp();
                    st.sl = res.sl();
                    st.entryQty = res.qty();
                    st.entrySide = "BUY";           // ✅ SPOT
                    st.entryOrderId = res.orderId();

                    log.info("[SCALPING] ✅ ENTRY OK (SPOT LONG) chatId={} qty={} entry={} tp={} sl={} orderId={}",
                            chatId,
                            st.entryQty != null ? st.entryQty.stripTrailingZeros().toPlainString() : "null",
                            st.entryPrice != null ? st.entryPrice.stripTrailingZeros().toPlainString() : "null",
                            st.tp != null ? st.tp.stripTrailingZeros().toPlainString() : "null",
                            st.sl != null ? st.sl.stripTrailingZeros().toPlainString() : "null",
                            st.entryOrderId
                    );

                    st.window.clear();
                    st.lastHoldReason = null;

                } catch (Exception e) {
                    log.error("[SCALPING] ❌ ENTRY failed chatId={} err={}", chatId, e.getMessage(), e);
                    pushHoldThrottled(chatId, symbol, st, "entry_failed", time);
                }
            }

            // =====================================================
            // EXIT
            // =====================================================
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {

                try {
                    var ex = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.SCALPING,
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

                        log.info("[SCALPING] ✅ EXIT OK chatId={} price={} (tp={} sl={})",
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

                        // очищаем линии на графике после закрытия
                        safeLive(() -> live.clearTpSl(chatId, StrategyType.SCALPING, symbol));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.SCALPING, symbol));
                    }

                } catch (Exception e) {
                    log.error("[SCALPING] ❌ EXIT failed chatId={} err={}", chatId, e.getMessage(), e);
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
            ScalpingStrategySettings scalping = scalpingSettingsService.getOrCreate(chatId);

            Instant loadedUpd = (loaded != null) ? toInstant(loaded.getUpdatedAt()) : null;
            Instant scalpingUpd = (scalping != null) ? scalping.getUpdatedAt() : null;

            String fp = buildSettingsFingerprint(loaded, scalping);

            boolean changed =
                    st.lastSettingsFingerprint == null ||
                    !st.lastSettingsFingerprint.equals(fp) ||
                    !Objects.equals(st.lastSettingsUpdatedAt, loadedUpd) ||
                    !Objects.equals(st.lastScalpingUpdatedAt, scalpingUpd);

            String oldSymbol = safeUpper(st.symbol);

            if (loaded != null) st.strategySettings = loaded;
            if (scalping != null) st.scalpingSettings = scalping;

            if (loaded != null) {
                String loadedSymbol = safeUpper(loaded.getSymbol());
                if (loadedSymbol != null) st.symbol = loadedSymbol;

                if (loaded.getExchangeName() != null) st.exchange = loaded.getExchangeName();
                if (loaded.getNetworkType() != null) st.network = loaded.getNetworkType();
            }

            st.lastSettingsLoadAt = now;

            if (changed) {
                st.lastSettingsFingerprint = fp;
                st.lastSettingsUpdatedAt = loadedUpd;
                st.lastScalpingUpdatedAt = scalpingUpd;

                log.info("[SCALPING] ⚙️ settings updated chatId={} symbol={} ex={} net={} windowSize={} thr={} spread={}",
                        chatId,
                        st.symbol,
                        st.exchange,
                        st.network,
                        scalping != null ? scalping.getWindowSize() : null,
                        scalping != null ? num(scalping.getPriceChangeThreshold()) : null,
                        scalping != null ? num(scalping.getSpreadThreshold()) : null
                );

                String newSymbol = safeUpper(st.symbol);
                if (oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol)) {
                    st.window.clear();
                    st.lastWindowHigh = null;
                    st.lastWindowLow = null;

                    // чистим зону и линии по СТАРОМУ символу, чтобы не оставался мусор
                    safeLive(() -> live.clearWindowZone(chatId, StrategyType.SCALPING, oldSymbol));
                    safeLive(() -> live.clearTpSl(chatId, StrategyType.SCALPING, oldSymbol));
                    safeLive(() -> live.clearPriceLines(chatId, StrategyType.SCALPING, oldSymbol));

                    st.lastHoldReason = null;
                }
            } else {
                log.debug("[SCALPING] settings unchanged chatId={} (skip log)", chatId);
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[SCALPING] ⚠️ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    // =====================================================
    // FINGERPRINT
    // =====================================================
    private String buildSettingsFingerprint(StrategySettings ss, ScalpingStrategySettings sc) {
        if (ss == null && sc == null) return "null";

        String symbol = ss != null ? safeUpper(ss.getSymbol()) : "null";
        String ex = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String net = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String tf = ss != null ? safe(ss.getTimeframe()) : "null";

        String tp = ss != null ? bd(ss.getTakeProfitPct()) : "null";
        String sl = ss != null ? bd(ss.getStopLossPct()) : "null";
        String comm = ss != null ? bd(ss.getCommissionPct()) : "null";
        String cooldown = ss != null && ss.getCooldownSeconds() != null ? String.valueOf(ss.getCooldownSeconds()) : "null";

        String window = sc != null && sc.getWindowSize() != null ? String.valueOf(sc.getWindowSize()) : "null";
        String thr = sc != null ? num(sc.getPriceChangeThreshold()) : "null";
        String spread = sc != null ? num(sc.getSpreadThreshold()) : "null";

        return symbol + "|" + ex + "|" + net + "|" + tf
               + "|" + window + "|" + thr + "|" + spread
               + "|" + tp + "|" + sl + "|" + comm + "|" + cooldown;
    }

    // =====================================================
    // LOAD
    // =====================================================
    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId, null, null)
                .stream()
                .filter(s -> s.getType() == StrategyType.SCALPING)
                .sorted(
                        Comparator
                                .comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                                .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                )
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "StrategySettings для SCALPING не найдены (chatId=" + chatId + ")"
                ));
    }

    // =====================================================
    // LIVE HELPERS (безопасно)
    // =====================================================
    private void safeLive(Runnable r) {
        try { r.run(); } catch (Exception ignored) {}
    }

    private void pushHoldThrottled(Long chatId, String symbol, LocalState st, String reason, Instant now) {
        if (symbol == null) return;

        // если причина та же и прошла меньше 2 секунд — не спамим
        if (Objects.equals(st.lastHoldReason, reason) && st.lastHoldAt != null) {
            long ms = Duration.between(st.lastHoldAt, now).toMillis();
            if (ms < 2000) return;
        }

        st.lastHoldReason = reason;
        st.lastHoldAt = now;
        safeLive(() -> live.pushSignal(chatId, StrategyType.SCALPING, symbol, null, Signal.hold(reason)));
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

    private static String bd(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
    }

    private static String num(Object v) {
        if (v == null) return "null";
        if (v instanceof BigDecimal bd) return bd.stripTrailingZeros().toPlainString();
        if (v instanceof Double d) return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        if (v instanceof Float f) return BigDecimal.valueOf(f.doubleValue()).stripTrailingZeros().toPlainString();
        if (v instanceof Integer i) return String.valueOf(i);
        if (v instanceof Long l) return String.valueOf(l);
        return String.valueOf(v);
    }
}
