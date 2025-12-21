package com.chicu.aitradebot.strategy.core.context;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.core.runtime.StrategyRuntimeState;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 🧠 RuntimeStrategyContext (V4)
 * Immutable snapshot данных для evaluate()
 */
@Getter
@Builder
public class RuntimeStrategyContext implements StrategyContext {

    // =================================================
    // IDENTIFICATION
    // =================================================

    private final Long chatId;
    private final String symbol;

    /**
     * 🔥 V4: тип стратегии (SCALPING / FIBONACCI / SMART_FUSION ...)
     */
    private final StrategyType strategyType;

    // =================================================
    // EXCHANGE CONTEXT
    // =================================================

    private final String exchange;
    private final NetworkType networkType;

    // =================================================
    // MARKET DATA
    // =================================================

    private final BigDecimal price;
    private final double[] closes;

    // =================================================
    // SETTINGS SNAPSHOT
    // =================================================

    private final Object settings;

    // =================================================
    // RUNTIME STATE
    // =================================================

    private final StrategyRuntimeState state;

    // =================================================
    // SAFETY
    // =================================================

    @Override
    public <T> T getTypedSettings(Class<T> clazz) {
        if (settings == null) {
            return null;
        }
        if (!clazz.isInstance(settings)) {
            throw new IllegalStateException(
                    "Settings type mismatch. Expected " + clazz.getSimpleName() +
                    ", got " + settings.getClass().getSimpleName()
            );
        }
        return clazz.cast(settings);
    }
}
