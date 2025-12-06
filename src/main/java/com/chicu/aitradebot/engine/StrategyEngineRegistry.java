package com.chicu.aitradebot.engine;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class StrategyEngineRegistry {

    private final Map<StrategyType, TradingStrategy> strategies = new EnumMap<>(StrategyType.class);

    public StrategyEngineRegistry(List<TradingStrategy> beans) {
        for (TradingStrategy bean : beans) {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            StrategyBinding binding = targetClass.getAnnotation(StrategyBinding.class);
            if (binding == null) {
                log.debug("⏭ Strategy bean {} без @StrategyBinding — пропускаем", targetClass.getName());
                continue;
            }
            StrategyType type = binding.value();
            if (strategies.containsKey(type)) {
                log.warn("⚠ Дублирующийся StrategyBinding для {}: {} и {}",
                        type, strategies.get(type).getClass().getName(), targetClass.getName());
            } else {
                strategies.put(type, bean);
                log.info("✅ Зарегистрирована стратегия {} → {}", type, targetClass.getSimpleName());
            }
        }
    }

    public Optional<TradingStrategy> getStrategy(StrategyType type) {
        return Optional.ofNullable(strategies.get(type));
    }

    public Map<StrategyType, TradingStrategy> getAll() {
        return Map.copyOf(strategies);
    }
}
