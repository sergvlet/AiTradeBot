package com.chicu.aitradebot.strategy.core.context;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.core.runtime.StrategyRuntimeState;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * 🧠 RuntimeStrategyContext (V4)
 * Immutable snapshot данных для evaluate()
 *
 * Важно:
 * - стратегия читает только этот контекст
 * - settings хранится как Object (тип зависит от strategyType)
 * - closes защищаем от мутаций (копия)
 */
@Getter
@Builder(toBuilder = true)
public class RuntimeStrategyContext implements StrategyContext {

    // =================================================
    // IDENTIFICATION
    // =================================================

    private final Long chatId;
    private final String symbol;

    /**
     * 🔥 V4: тип стратегии (SCALPING / FIBONACCI / SMART_FUSION ...)
     * ВАЖНО: должен быть задан builder'ом. (Не через StrategyContext fallback)
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

    /**
     * Закрытия свечей (старые → новые).
     * Держим ссылку только на копию, чтобы никто снаружи не мутировал snapshot.
     */
    private final double[] closes;

    // =================================================
    // SETTINGS SNAPSHOT
    // =================================================

    /**
     * Snapshot настроек стратегии (тип зависит от strategyType).
     * Например: ScalpingStrategySettings / FibonacciGridStrategySettings / StrategySettings / ...
     */
    private final Object settings;

    // =================================================
    // RUNTIME STATE
    // =================================================

    private final StrategyRuntimeState state;

    // =================================================
    // StrategyContext contract
    // =================================================

    @Override
    public Object getSettings() {
        return settings;
    }

    /**
     * Переопределяем, чтобы не зависеть от fallback-логики интерфейса.
     * В V4 "истина" — поле strategyType.
     */
    @Override
    public StrategyType getStrategyType() {
        return strategyType;
    }

    /**
     * Защита массива от внешней мутации:
     * если кто-то передал массив и потом его меняет — snapshot не должен "плыть".
     */
    public static class RuntimeStrategyContextBuilder {

        public RuntimeStrategyContextBuilder closes(double[] closes) {
            this.closes = (closes == null) ? null : Arrays.copyOf(closes, closes.length);
            return this;
        }
    }

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
