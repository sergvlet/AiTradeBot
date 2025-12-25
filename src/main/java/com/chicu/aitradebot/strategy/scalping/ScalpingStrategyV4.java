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
    private static final int MIN_WINDOW = 5;

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
        Long exitOrderId;

        BigDecimal entryQty;
        String entrySide;
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

        log.info("▶ SCALPING START chatId={} {} {}", chatId, st.exchange, st.symbol);
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

        log.info("⏹ SCALPING STOP chatId={} {}", chatId, symbol);
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

        LocalState st = states.get(chatId);
        if (st == null || !st.active) return;
        if (price == null || price.signum() <= 0) return;

        Instant time = ts != null ? ts : Instant.now();
        refreshSettingsIfNeeded(chatId, st, time);

        StrategySettings strategy = st.strategySettings;
        ScalpingStrategySettings cfg = st.scalpingSettings;
        String symbol = st.symbol;

        live.pushPriceTick(chatId, StrategyType.SCALPING, symbol, price, time);

        int windowSize = Math.max(cfg.getWindowSize(), MIN_WINDOW);
        st.window.addLast(price);
        while (st.window.size() > windowSize) st.window.removeFirst();

        if (st.window.size() < windowSize) {
            live.pushSignal(chatId, StrategyType.SCALPING, symbol, null,
                    Signal.hold("warming up"));
            return;
        }

        BigDecimal first = st.window.getFirst();
        BigDecimal last = st.window.getLast();

        double diffPct = last.subtract(first)
                                 .divide(first, 8, RoundingMode.HALF_UP)
                                 .doubleValue() * 100.0;

        if (!st.inPosition && Math.abs(diffPct) >= cfg.getPriceChangeThreshold()) {

            // ===== BALANCE SNAPSHOT =====
            AccountBalanceSnapshot snapshot =
                    accountBalanceService.getSnapshot(
                            chatId,
                            StrategyType.SCALPING,
                            strategy.getExchangeName(),
                            strategy.getNetworkType()
                    );

            BigDecimal available =
                    snapshot != null ? snapshot.getSelectedFreeBalance() : null;

            if (available == null || available.signum() <= 0) {
                live.pushSignal(chatId, StrategyType.SCALPING, symbol, null,
                        Signal.hold("no balance"));
                return;
            }

            BigDecimal maxAllowed =
                    resolveMaxExposureAmount(strategy, available);

            BigDecimal tradeAmount =
                    pickTradeAmount(cfg.getOrderVolume(), maxAllowed);

            if (tradeAmount.signum() <= 0) {
                live.pushSignal(chatId, StrategyType.SCALPING, symbol, null,
                        Signal.hold("limit is zero"));
                return;
            }

            BigDecimal qty =
                    tradeAmount.divide(price, 8, RoundingMode.DOWN);

            if (qty.signum() <= 0) return;

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

            try {
                Order entry = orderService.placeMarket(
                        chatId, symbol, entrySide, qty, price, StrategyType.SCALPING.name()
                );
                st.entryOrderId = entry != null ? entry.getId() : null;
                st.entryQty = qty;
                st.entrySide = entrySide;
            } catch (Exception e) {
                st.inPosition = false;
                st.entryQty = null;
                return;
            }

            live.pushPriceLine(chatId, StrategyType.SCALPING, symbol, "ENTRY", price);
            live.pushTpSl(chatId, StrategyType.SCALPING, symbol, st.tp, st.sl);

            st.window.clear();
            return;
        }

        // ===== EXIT =====
        if (st.inPosition && st.entryQty != null) {

            boolean hitTp = st.isLong ? price.compareTo(st.tp) >= 0 : price.compareTo(st.tp) <= 0;
            boolean hitSl = st.isLong ? price.compareTo(st.sl) <= 0 : price.compareTo(st.sl) >= 0;

            if (hitTp || hitSl) {

                String exitSide = st.isLong ? "SELL" : "BUY";

                try {
                    orderService.placeMarket(
                            chatId, symbol, exitSide, st.entryQty, price, StrategyType.SCALPING.name()
                    );
                } catch (Exception e) {
                    return;
                }

                st.inPosition = false;
                st.entryQty = null;
                st.window.clear();

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
                .orElse(null);
    }

    private double safePct(BigDecimal pct) {
        return pct != null ? pct.doubleValue() / 100.0 : 0.0;
    }

    private BigDecimal resolveMaxExposureAmount(
            StrategySettings settings,
            BigDecimal available
    ) {
        if (available == null || available.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        // 1️⃣ Фиксированная сумма
        if (settings.getMaxExposureUsd() != null) {
            return settings.getMaxExposureUsd().max(BigDecimal.ZERO);
        }

        // 2️⃣ Процент от баланса (Integer → BigDecimal)
        if (settings.getMaxExposurePct() != null) {
            int pct = settings.getMaxExposurePct();
            if (pct <= 0) return BigDecimal.ZERO;

            return available
                    .multiply(BigDecimal.valueOf(pct))
                    .divide(BigDecimal.valueOf(100), 8, RoundingMode.DOWN);
        }

        // 3️⃣ NONE → весь баланс
        return available;
    }



    private BigDecimal pickTradeAmount(BigDecimal desired, BigDecimal maxAllowed) {
        if (desired == null || desired.signum() <= 0) return maxAllowed;
        return desired.min(maxAllowed);
    }
}
