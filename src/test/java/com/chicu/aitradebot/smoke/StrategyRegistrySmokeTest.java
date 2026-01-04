package com.chicu.aitradebot.smoke;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.registry.StrategyRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class StrategyRegistrySmokeTest {

    @Autowired
    StrategyRegistry registry;

    @Test
    void shouldHaveCoreStrategiesRegistered() {
        assertNotNull(registry);
        assertNotNull(registry.get(StrategyType.SCALPING));
        assertNotNull(registry.get(StrategyType.RSI_EMA));
        assertNotNull(registry.get(StrategyType.SMART_FUSION));
        assertNotNull(registry.get(StrategyType.ML_INVEST));
    }
}
