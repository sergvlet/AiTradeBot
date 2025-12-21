package com.chicu.aitradebot.strategy.core.context;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import com.chicu.aitradebot.strategy.core.runtime.StrategyRuntimeState;

import java.math.BigDecimal;
import java.util.List;

/**
 * 🧱 Builder для StrategyContext (V4)
 * ЕДИНСТВЕННАЯ точка сборки контекста стратегии
 */
public class StrategyContextBuilder {

    private Long chatId;
    private String symbol;
    private String exchange;
    private NetworkType networkType;

    // 🔥 V4: тип стратегии
    private StrategyType strategyType;

    private BigDecimal price;
    private double[] closes;

    private Object settings;
    private StrategyRuntimeState state;

    // =================================================
    // CHAIN SETTERS
    // =================================================

    public StrategyContextBuilder chatId(Long chatId) {
        this.chatId = chatId;
        return this;
    }

    public StrategyContextBuilder symbol(String symbol) {
        this.symbol = symbol;
        return this;
    }

    public StrategyContextBuilder exchange(String exchange) {
        this.exchange = exchange;
        return this;
    }

    public StrategyContextBuilder networkType(NetworkType networkType) {
        this.networkType = networkType;
        return this;
    }

    public StrategyContextBuilder strategyType(StrategyType strategyType) {
        this.strategyType = strategyType;
        return this;
    }

    public StrategyContextBuilder price(BigDecimal price) {
        this.price = price;
        return this;
    }

    /**
     * Быстрый путь — готовый массив closes
     */
    public StrategyContextBuilder closes(double[] closes) {
        this.closes = (closes != null) ? closes : new double[0];
        return this;
    }

    /**
     * Альтернатива — собрать closes из CandleProvider
     */
    public StrategyContextBuilder closesFromCandles(List<CandleProvider.Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            this.closes = new double[0];
            return this;
        }
        double[] arr = new double[candles.size()];
        for (int i = 0; i < candles.size(); i++) {
            arr[i] = candles.get(i).close();
        }
        this.closes = arr;
        return this;
    }

    public StrategyContextBuilder settings(Object settings) {
        this.settings = settings;
        return this;
    }

    public StrategyContextBuilder state(StrategyRuntimeState state) {
        this.state = state;
        return this;
    }

    // =================================================
    // BUILD
    // =================================================

    public StrategyContext build() {

        if (chatId == null) {
            throw new IllegalStateException("StrategyContext: chatId is null");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalStateException("StrategyContext: symbol is empty");
        }
        if (price == null) {
            throw new IllegalStateException("StrategyContext: price is null");
        }
        if (strategyType == null) {
            throw new IllegalStateException("StrategyContext: strategyType is null");
        }

        double[] safeCloses = (closes != null) ? closes : new double[0];

        return RuntimeStrategyContext.builder()
                .chatId(chatId)
                .symbol(symbol)
                .exchange(exchange)
                .networkType(networkType != null ? networkType : NetworkType.MAINNET)
                .strategyType(strategyType)
                .price(price)
                .closes(safeCloses)
                .settings(settings)
                .state(state)
                .build();
    }
}
