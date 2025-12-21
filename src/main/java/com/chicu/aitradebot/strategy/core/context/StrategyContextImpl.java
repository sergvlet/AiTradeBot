package com.chicu.aitradebot.strategy.core.context;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.strategy.core.runtime.StrategyRuntimeState;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

@RequiredArgsConstructor
public class StrategyContextImpl implements StrategyContext {

    private final Long chatId;
    private final String symbol;

    private final BigDecimal price;
    private final double[] closes;

    private final Object settings;
    private final StrategyRuntimeState state;

    // ==============================
    // IDENTITY
    // ==============================
    @Override
    public Long getChatId() {
        return chatId;
    }

    @Override
    public String getSymbol() {
        return symbol;
    }

    @Override
    public String getExchange() {
        return "";
    }

    @Override
    public NetworkType getNetworkType() {
        return null;
    }

    // ==============================
    // PRICE
    // ==============================
    @Override
    public BigDecimal getPrice() {
        return price;
    }

    // ==============================
    // CANDLES
    // ==============================
    @Override
    public double[] getCloses() {
        return closes;
    }

    // ==============================
    // SETTINGS
    // ==============================
    @Override
    public Object getSettings() {
        return settings;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getTypedSettings(Class<T> clazz) {
        if (settings == null) return null;

        if (!clazz.isAssignableFrom(settings.getClass())) {
            throw new IllegalStateException(
                    "Settings type mismatch. Expected " + clazz.getSimpleName() +
                    ", got " + settings.getClass().getSimpleName()
            );
        }
        return (T) settings;
    }

    // ==============================
    // STATE
    // ==============================
    @Override
    public StrategyRuntimeState getState() {
        return state;
    }
}
