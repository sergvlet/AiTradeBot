package com.chicu.aitradebot.strategy.mlinvest;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Заглушка ML Invest стратегии под новую архитектуру TradingStrategy v4.
 * Логика ML-предсказаний будет добавлена позже.
 */
@Slf4j
@Component
@StrategyBinding(StrategyType.ML_INVEST)
public class MlInvestStrategy implements TradingStrategy {

    /**
     * Состояние на каждого пользователя (chatId).
     */
    private static class State {
        boolean active;
        String symbol;
        Instant startedAt;
    }

    private final Map<Long, State> states = new ConcurrentHashMap<>();

    // =====================================================================
    // START / STOP
    // =====================================================================

    @Override
    public synchronized void start(Long chatId, String symbol) {
        State st = states.computeIfAbsent(chatId, id -> new State());
        st.active = true;
        st.symbol = symbol != null ? symbol : st.symbol;
        st.startedAt = Instant.now();

        log.info("🤖 ML Invest strategy STARTED chatId={} symbol={}", chatId, st.symbol);
    }

    @Override
    public synchronized void stop(Long chatId, String symbol) {
        State st = states.get(chatId);
        if (st == null || !st.active) {
            log.warn("⚠️ ML Invest already stopped chatId={}", chatId);
            return;
        }

        st.active = false;
        log.info("🧠 ML Invest strategy STOPPED chatId={} symbol={}", chatId, st.symbol);
    }

    @Override
    public boolean isActive(Long chatId) {
        State st = states.get(chatId);
        return st != null && st.active;
    }

    @Override
    public Instant getStartedAt(Long chatId) {
        State st = states.get(chatId);
        return st != null ? st.startedAt : null;
    }

    @Override
    public String getThreadName(Long chatId) {
        State st = states.get(chatId);
        String symbol = (st != null && st.symbol != null) ? st.symbol : "UNKNOWN";
        return "ML-INVEST-" + chatId + "-" + symbol;
    }

    // =====================================================================
    // PRICE UPDATES
    // =====================================================================

    @Override
    public void onPriceUpdate(Long chatId,
                              String symbol,
                              BigDecimal price,
                              Instant ts) {

        State st = states.get(chatId);
        if (st == null || !st.active) {
            return;
        }

        // Здесь позже будет:
        //  - запрос к ML-модели
        //  - решение BUY/SELL/HOLD
        //  - вызов OrderService / ExchangeClient и т.п.
        log.debug("📡 ML Invest tick chatId={} symbol={} price={} at={}",
                chatId, symbol, price, ts);
    }
}
