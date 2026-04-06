package com.chicu.aitradebot.ai.tuning.config;

import com.chicu.aitradebot.strategy.windowscalping.WindowScalpingTunerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        WindowScalpingTunerProperties.class
})
public class AiTuningConfig {
}