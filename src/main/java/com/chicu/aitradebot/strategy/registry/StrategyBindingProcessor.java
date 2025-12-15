package com.chicu.aitradebot.strategy.registry;

import com.chicu.aitradebot.strategy.core.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyBindingProcessor implements BeanPostProcessor {

    private final StrategyRegistry registry;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // ищем стратегии
        if (bean instanceof TradingStrategy strategy) {

            // ищем аннотацию
            StrategyBinding binding = bean.getClass().getAnnotation(StrategyBinding.class);

            if (binding != null) {
                registry.register(binding.value(), strategy);

                log.info("📌 Strategy registered: {} → {}", 
                    binding.value(), bean.getClass().getSimpleName());
            }
        }

        return bean;
    }
}
