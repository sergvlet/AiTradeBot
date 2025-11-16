package com.chicu.aitradebot.strategy.registry;

import com.chicu.aitradebot.common.enums.StrategyType;
import java.lang.annotation.*;

/**
 * Аннотация для автоматической регистрации стратегии в реестре.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface StrategyBinding {
    StrategyType value();
}
