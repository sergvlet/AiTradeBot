package com.chicu.aitradebot.strategy.registry;

import com.chicu.aitradebot.common.enums.StrategyType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Аннотация для автоматической регистрации стратегии в StrategyRegistry.

 * Пример:
 *   @StrategyBinding(StrategyType.SCALPING)
 *   public class ScalpingStrategy implements TradingStrategy { ... }

 * Обрабатывается StrategyBindingProcessor, который вытаскивает value()
 * и регистрирует бин в StrategyRegistry.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface StrategyBinding {
    StrategyType value();
}
