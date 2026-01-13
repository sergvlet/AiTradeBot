package com.chicu.aitradebot.strategy.registry;

import com.chicu.aitradebot.strategy.core.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StrategyBindingProcessor implements BeanPostProcessor {

    private final StrategyRegistry registry;

    public StrategyBindingProcessor(StrategyRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Object postProcessAfterInitialization(@NotNull Object bean, @NotNull String beanName) throws BeansException {

        // Прокси тоже обычно instanceof TradingStrategy, так что это ок.
        if (!(bean instanceof TradingStrategy strategy)) {
            return bean;
        }

        // ✅ КЛЮЧЕВО: берём реальный класс (а не proxy-class)
        Class<?> targetClass = AopUtils.getTargetClass(bean);

        // ✅ Надёжный поиск аннотации (учитывает наследование/мета-аннотации)
        StrategyBinding binding =
                AnnotatedElementUtils.findMergedAnnotation(targetClass, StrategyBinding.class);

        if (binding == null) {
            // Можно оставить debug, чтобы не шуметь в логах
            log.debug("Strategy bean has no @StrategyBinding: beanName={}, class={}",
                    beanName, targetClass.getName());
            return bean;
        }

        registry.register(binding.value(), strategy);

        log.info("✅ Strategy bound: {} -> {} (beanName={})",
                binding.value(), targetClass.getSimpleName(), beanName);

        return bean;
    }
}
