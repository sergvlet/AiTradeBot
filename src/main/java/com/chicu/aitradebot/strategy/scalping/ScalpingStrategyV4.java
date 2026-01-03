package com.chicu.aitradebot.strategy.scalping;

import com.chicu.aitradebot.account.AccountBalanceService;
import com.chicu.aitradebot.account.AccountBalanceSnapshot;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@StrategyBinding(StrategyType.SCALPING)
@Slf4j
@Component
@RequiredArgsConstructor
public class ScalpingStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);

    private final StrategyLivePublisher live;
    private final ScalpingStrategySettingsService scalpingSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final OrderService orderService;
    private final AccountBalanceService accountBalanceService;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    // =====================================================
    // LOCAL STATE
    // =====================================================
    private static class LocalState {
        Instant startedAt;
        boolean active;

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
        st.symbol = strategy.getSymbol();
        st.exchange = strategy.getExchangeName();
        st.network = strategy.getNetworkType();
        st.lastSettingsLoadAt = Instant.now();

        states.put(chatId, st);

        live.pushState(chatId, StrategyType.SCALPING, st.symbol, true);
        live.pushSignal(chatId, StrategyType.SCALPING, st.symbol, null,
                Signal.hold("started"));
    }

    @Override
    public void stop(Long chatId, String ignored) {

        LocalState st = states.remove(chatId);
        if (st == null) return;

        String symbol = st.symbol;

        live.clearTpSl(chatId, StrategyType.SCALPING, symbol);
        live.clearPriceLines(chatId, StrategyType.SCALPING, symbol);
        live.clearWindowZone(chatId, StrategyType.SCALPING, symbol);
        live.pushState(chatId, StrategyType.SCALPING, symbol, false);
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
    public void onPriceUpdate(Long chatId, String ignored, BigDecimal price, Instant ts) {
        log.info("[SCALPING] ⏱ onPriceUpdate | chatId={}, price={}, ts={}", chatId, price, ts);

        LocalState st = states.get(chatId);
        if (st == null || !st.active) {
            log.warn("[SCALPING] ⛔ Strategy inactive or not found for chatId={}", chatId);
            return;
        }
        if (price == null || price.signum() <= 0) {
            log.warn("[SCALPING] ⚠ Invalid price received: {}", price);
            return;
        }

        Instant time = ts != null ? ts : Instant.now();
        refreshSettingsIfNeeded(chatId, st, time);

        StrategySettings strategy = st.strategySettings;
        ScalpingStrategySettings cfg = st.scalpingSettings;
        String symbol = st.symbol;

        live.pushPriceTick(chatId, StrategyType.SCALPING, symbol, price, time);

        int windowSize = cfg.getWindowSize();
        st.window.addLast(price);
        while (st.window.size() > windowSize) st.window.removeFirst();

        if (st.window.size() < windowSize) {
            log.info("[SCALPING] 🔄 Warming up window: {}/{}", st.window.size(), windowSize);
            live.pushSignal(chatId, StrategyType.SCALPING, symbol, null, Signal.hold("warming up"));
            return;
        }

        BigDecimal first = st.window.getFirst();
        BigDecimal last = st.window.getLast();

        double diffPct = last.subtract(first)
                                 .divide(first, 8, RoundingMode.HALF_UP)
                                 .doubleValue() * 100.0;

        log.debug("[SCALPING] 📊 Price diffPct: {}", diffPct);

        // ✅ Отправка window_zone на график
        double thresholdPct = cfg.getPriceChangeThreshold();
        if (thresholdPct > 0) {
            BigDecimal high = last.multiply(BigDecimal.valueOf(1 + thresholdPct / 100.0));
            BigDecimal low  = last.multiply(BigDecimal.valueOf(1 - thresholdPct / 100.0));
            log.info("[SCALPING] 📐 pushWindowZone: high={}, low={}, threshold={}%", high, low, thresholdPct);
            live.pushWindowZone(chatId, StrategyType.SCALPING, symbol, high, low);

        }

        // ENTRY
        if (!st.inPosition && Math.abs(diffPct) >= thresholdPct) {

            Integer cooldown = strategy.getCooldownSeconds();
            if (cooldown != null && cooldown > 0 && st.lastTradeClosedAt != null) {
                long passed = Duration.between(st.lastTradeClosedAt, time).getSeconds();
                if (passed < cooldown) {
                    log.info("[SCALPING] 🕒 Cooldown active: {}s remaining", cooldown - passed);
                    live.pushSignal(chatId, StrategyType.SCALPING, symbol, null, Signal.hold("cooldown"));
                    return;
                }
            }

            AccountBalanceSnapshot snapshot = accountBalanceService.getSnapshot(
                    chatId, StrategyType.SCALPING, strategy.getExchangeName(), strategy.getNetworkType());

            if (snapshot == null) {
                log.warn("[SCALPING] ⚠ No account snapshot available for chatId={}", chatId);
                return;
            }

            BigDecimal available = snapshot.getSelectedFreeBalance();
            if (available == null || available.signum() <= 0) {
                log.warn("[SCALPING] ⚠ No available balance");
                return;
            }

            BigDecimal maxAllowed = resolveMaxExposureAmount(strategy, available);
            BigDecimal tradeAmount = resolveTradeAmount(strategy, available, maxAllowed);
            if (tradeAmount.signum() <= 0) {
                log.warn("[SCALPING] ⚠ Trade amount resolved to zero");
                return;
            }

            BigDecimal qty = tradeAmount.divide(price, 8, RoundingMode.DOWN);
            if (qty.signum() <= 0) {
                log.warn("[SCALPING] ⚠ Calculated qty <= 0");
                return;
            }

            st.inPosition = true;
            st.isLong = diffPct > 0;
            st.entryPrice = price;

            double tpPct = safePct(strategy.getTakeProfitPct());
            double slPct = safePct(strategy.getStopLossPct());

            st.tp = st.isLong
                    ? price.multiply(BigDecimal.valueOf(1 + tpPct))
                    : price.multiply(BigDecimal.valueOf(1 - tpPct));

            st.sl = st.isLong
                    ? price.multiply(BigDecimal.valueOf(1 - slPct))
                    : price.multiply(BigDecimal.valueOf(1 + slPct));

            String entrySide = st.isLong ? "BUY" : "SELL";

            Order entry = orderService.placeMarket(
                    chatId, symbol, entrySide, qty, price, StrategyType.SCALPING.name());

            st.entryQty = qty;
            st.entrySide = entrySide;

            log.info("[SCALPING] ✅ ENTRY: chatId={}, side={}, price={}, qty={}", chatId, entrySide, price, qty);

            live.pushPriceLine(chatId, StrategyType.SCALPING, symbol, "ENTRY", price);
            live.pushTpSl(chatId, StrategyType.SCALPING, symbol, st.tp, st.sl);
            live.pushTrade(chatId, StrategyType.SCALPING, symbol, entrySide, price, qty, time);

            st.window.clear();
        }

        // EXIT
        if (st.inPosition && st.entryQty != null) {
            boolean hitTp = st.isLong ? price.compareTo(st.tp) >= 0 : price.compareTo(st.tp) <= 0;
            boolean hitSl = st.isLong ? price.compareTo(st.sl) <= 0 : price.compareTo(st.sl) >= 0;

            if (hitTp || hitSl) {
                String exitSide = st.isLong ? "SELL" : "BUY";

                orderService.placeMarket(chatId, symbol, exitSide, st.entryQty, price, StrategyType.SCALPING.name());

                log.info("[SCALPING] ✅ EXIT: chatId={}, side={}, price={}, qty={}, reason={}",
                        chatId, exitSide, price, st.entryQty, hitTp ? "TP" : "SL");

                live.pushTrade(chatId, StrategyType.SCALPING, symbol, exitSide, price, st.entryQty, time);

                st.inPosition = false;
                st.entryQty = null;
                st.lastTradeClosedAt = time;

                live.clearTpSl(chatId, StrategyType.SCALPING, symbol);
                live.clearPriceLines(chatId, StrategyType.SCALPING, symbol);
            }
        }
    }

    // =====================================================
    // HELPERS
    // =====================================================
    private void refreshSettingsIfNeeded(Long chatId, LocalState st, Instant now) {
        if (st.lastSettingsLoadAt == null ||
            Duration.between(st.lastSettingsLoadAt, now).compareTo(SETTINGS_REFRESH_EVERY) >= 0) {

            StrategySettings loaded = loadStrategySettings(chatId);
            st.strategySettings = loaded;
            st.scalpingSettings = scalpingSettingsService.getOrCreate(chatId);
            st.symbol = loaded.getSymbol();
            st.exchange = loaded.getExchangeName();
            st.network = loaded.getNetworkType();
            st.lastSettingsLoadAt = now;
        }
    }

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId, null, null)
                .stream()
                .filter(s -> s.getType() == StrategyType.SCALPING)
                .findFirst()
                .orElseThrow();
    }

    private double safePct(BigDecimal pct) {
        return pct != null ? pct.doubleValue() / 100.0 : 0.0;
    }

    private BigDecimal resolveMaxExposureAmount(
            StrategySettings settings,
            BigDecimal available
    ) {
        if (settings.getMaxExposureUsd() != null)
            return settings.getMaxExposureUsd();

        if (settings.getMaxExposurePct() != null)
            return available
                    .multiply(BigDecimal.valueOf(settings.getMaxExposurePct()))
                    .divide(BigDecimal.valueOf(100), 8, RoundingMode.DOWN);

        return available;
    }

    private BigDecimal resolveTradeAmount(
            StrategySettings settings,
            BigDecimal available,
            BigDecimal maxAllowed
    ) {
        if (available == null || available.signum() <= 0) return BigDecimal.ZERO;
        if (maxAllowed == null || maxAllowed.signum() <= 0) return BigDecimal.ZERO;

        // 1) Фиксированная сумма (capitalUsd) — приоритетнее
        if (settings.getCapitalUsd() != null && settings.getCapitalUsd().signum() > 0) {
            return settings.getCapitalUsd().min(maxAllowed);
        }

        // 2) Процент от доступного баланса (riskPerTradePct)
        BigDecimal riskPct = settings.getRiskPerTradePct();
        if (riskPct != null && riskPct.signum() > 0) {

            // available * pct / 100
            BigDecimal byPct = available
                    .multiply(riskPct)
                    .divide(BigDecimal.valueOf(100), 8, RoundingMode.DOWN);

            return byPct.min(maxAllowed);
        }

        // 3) Если ничего не задано — используем максимум по лимитам
        return maxAllowed;
    }

}
