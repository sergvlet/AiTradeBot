package com.chicu.aitradebot.strategy.scalping;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScalpingStrategyV4 implements TradingStrategy {

    private final StrategyLivePublisher live;
    private final ScalpingStrategySettingsService settingsService;

    /**
     * chatId:symbol → локальное состояние стратегии
     * НЕ StrategyRuntimeState
     */
    private final Map<String, LocalState> states = new ConcurrentHashMap<>();

    // =====================================================
    // LOCAL STATE (НЕ ЗАВИСИТ ОТ StrategyRuntimeState)
    // =====================================================
    private static class LocalState {
        Instant startedAt;
        boolean active;

        Deque<BigDecimal> window = new ArrayDeque<>();

        BigDecimal entryPrice;
        BigDecimal takeProfit;
        BigDecimal stopLoss;
    }

    // =====================================================
    // START / STOP
    // =====================================================
    @Override
    public void start(Long chatId, String symbol) {
        LocalState st = getState(chatId, symbol);
        st.active = true;
        st.startedAt = Instant.now();

        live.pushState(chatId, StrategyType.SCALPING, symbol, true);
        live.pushSignal(chatId, StrategyType.SCALPING, symbol, null,
                com.chicu.aitradebot.strategy.core.signal.Signal.hold("started"));

        log.info("▶ SCALPING V4 START chatId={} symbol={}", chatId, symbol);
    }

    @Override
    public void stop(Long chatId, String symbol) {
        LocalState st = states.remove(key(chatId, symbol));
        if (st == null) return;

        live.clearTpSl(chatId, StrategyType.SCALPING, symbol);
        live.clearPriceLines(chatId, StrategyType.SCALPING, symbol);
        live.clearWindowZone(chatId, StrategyType.SCALPING, symbol);
        live.pushState(chatId, StrategyType.SCALPING, symbol, false);

        log.info("⏹ SCALPING V4 STOP chatId={} symbol={}", chatId, symbol);
    }

    @Override
    public boolean isActive(Long chatId) {
        return states.values().stream().anyMatch(s -> s.active);
    }

    @Override
    public Instant getStartedAt(Long chatId) {
        return states.values().stream()
                .map(s -> s.startedAt)
                .findFirst()
                .orElse(null);
    }

    // =====================================================
    // MAIN LOGIC — V4 LIVE
    // =====================================================
    @Override
    public void onPriceUpdate(Long chatId, String symbol, BigDecimal price, Instant ts) {

        if (price == null || price.signum() <= 0) return;

        LocalState st = getState(chatId, symbol);
        if (!st.active) return;

        ScalpingStrategySettings cfg = settingsService.getOrCreate(chatId);
        Instant time = ts != null ? ts : Instant.now();

        // цена ВСЕГДА
        live.pushPriceTick(chatId, StrategyType.SCALPING, symbol, price, time);

        // окно
        int windowSize = Math.max(cfg.getWindowSize(), 5);
        st.window.addLast(price);
        while (st.window.size() > windowSize) {
            st.window.removeFirst();
        }

        if (st.window.size() < windowSize) {
            live.pushSignal(chatId, StrategyType.SCALPING, symbol, null,
                    com.chicu.aitradebot.strategy.core.signal.Signal.hold("warming up"));
            return;
        }

        BigDecimal min = st.window.stream().min(BigDecimal::compareTo).orElse(price);
        BigDecimal max = st.window.stream().max(BigDecimal::compareTo).orElse(price);

        // зона окна ВСЕГДА
        live.pushWindowZone(chatId, StrategyType.SCALPING, symbol, max, min);

        BigDecimal first = st.window.getFirst();
        BigDecimal last  = st.window.getLast();

        if (first.signum() <= 0) {
            live.pushSignal(chatId, StrategyType.SCALPING, symbol, null,
                    com.chicu.aitradebot.strategy.core.signal.Signal.hold("bad window"));
            return;
        }

        double diffPct = last.subtract(first)
                .divide(first, 6, BigDecimal.ROUND_HALF_UP)
                .doubleValue() * 100.0;

        double threshold = cfg.getPriceChangeThreshold();

        // ENTRY
        if (st.entryPrice == null && Math.abs(diffPct) >= threshold) {

            st.entryPrice = price;
            st.takeProfit = price.multiply(
                    BigDecimal.valueOf(1 + cfg.getTakeProfitPct() / 100.0)
            );
            st.stopLoss = price.multiply(
                    BigDecimal.valueOf(1 - cfg.getStopLossPct() / 100.0)
            );

            live.pushPriceLine(chatId, StrategyType.SCALPING, symbol, "ENTRY", st.entryPrice);
            live.pushTpSl(chatId, StrategyType.SCALPING, symbol, st.takeProfit, st.stopLoss);

            live.pushSignal(chatId, StrategyType.SCALPING, symbol, null,
                    diffPct > 0
                            ? com.chicu.aitradebot.strategy.core.signal.Signal.buy(diffPct, "breakout up")
                            : com.chicu.aitradebot.strategy.core.signal.Signal.sell(diffPct, "breakout down")
            );
            return;
        }

        // HOLD ВСЕГДА
        live.pushSignal(chatId, StrategyType.SCALPING, symbol, null,
                com.chicu.aitradebot.strategy.core.signal.Signal.hold("no signal"));
    }

    // =====================================================
    // HELPERS
    // =====================================================
    private LocalState getState(Long chatId, String symbol) {
        return states.computeIfAbsent(key(chatId, symbol), k -> new LocalState());
    }

    private String key(Long chatId, String symbol) {
        return chatId + ":" + symbol;
    }
}
