package com.chicu.aitradebot.strategy.registry;

import com.chicu.aitradebot.strategy.core.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StrategyBindingProcessor implements BeanPostProcessor {

    private final StrategyRegistry registry;

    public StrategyBindingProcessor(StrategyRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {

        if (!(bean instanceof TradingStrategy strategy)) {
            return bean;
        }

        // 🔑 КЛЮЧЕВО: корректно работаем с proxy
        StrategyBinding binding =
                AnnotationUtils.findAnnotation(strategy.getClass(), StrategyBinding.class);

        if (binding == null) {
            return bean;
        }

        registry.register(binding.value(), strategy);

        return bean;
    }
}
