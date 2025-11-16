package com.chicu.aitradebot.strategy.registry;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.core.StrategySettingsProvider;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategySettingsResolver {

    private final ApplicationContext context;

    private final Map<StrategyType, StrategySettingsProvider<?>> providers =
            new EnumMap<>(StrategyType.class);

    @PostConstruct
    public void init() {
        Map<String, StrategySettingsProvider> beans = context.getBeansOfType(StrategySettingsProvider.class);

        beans.forEach((name, provider) -> {
            for (StrategyType type : StrategyType.values()) {
                if (name.equalsIgnoreCase(type.name())) {
                    providers.put(type, provider);
                    log.info("🔧 Registered settings provider: {} -> {}", type, name);
                }
            }
        });

        log.info("📦 StrategySettingsResolver initialized ({} providers)", providers.size());
    }

    public StrategySettingsProvider<?> getProvider(StrategyType type) {
        return providers.get(type);
    }
}
