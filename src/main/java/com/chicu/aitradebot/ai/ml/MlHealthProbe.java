package com.chicu.aitradebot.ai.ml;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MlHealthProbe implements ApplicationRunner {

    private final MlClient mlClient;

    @Override
    public void run(ApplicationArguments args) {
        try {
            var node = mlClient.health();
            log.info("✅ ML service OK: {}", node.toString());
        } catch (Exception e) {
            // В проде лучше НЕ валить приложение, просто предупреждение.
            log.warn("⚠️ ML service NOT available: {}", e.getMessage());
        }
    }
}
