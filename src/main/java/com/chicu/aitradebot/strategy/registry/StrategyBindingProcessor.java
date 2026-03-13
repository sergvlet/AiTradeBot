package com.chicu.aitradebot.strategy.registry;

import com.chicu.aitradebot.strategy.core.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class StrategyBindingProcessor implements BeanPostProcessor {

    private final StrategyRegistry registry;

    /**
     * Защита от повторной регистрации одного и того же bean/type при прокси/реинициализации.
     */
    private final Set<String> processed = ConcurrentHashMap.newKeySet();

    public StrategyBindingProcessor(StrategyRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Object postProcessAfterInitialization(@NotNull Object bean, @NotNull String beanName) throws BeansException {

        if (!(bean instanceof TradingStrategy strategy)) {
            return bean;
        }

        Class<?> targetClass = AopUtils.getTargetClass(bean);
        if (targetClass == null) {
            targetClass = bean.getClass();
        }

        StrategyBinding binding =
                AnnotatedElementUtils.findMergedAnnotation(targetClass, StrategyBinding.class);

        if (binding == null) {
            log.debug("Strategy bean has no @StrategyBinding: beanName={}, class={}",
                    beanName, targetClass.getName());
            return bean;
        }

        String dedupKey = binding.value().name() + "|" + beanName + "|" + targetClass.getName();
        if (!processed.add(dedupKey)) {
            log.debug("Strategy binding already processed: type={} beanName={} class={}",
                    binding.value(), beanName, targetClass.getName());
            return bean;
        }

        registry.register(binding.value(), strategy);

        log.info("✅ Strategy bound: {} -> {} (beanName={})",
                binding.value(), targetClass.getSimpleName(), beanName);

        return bean;
    }
}