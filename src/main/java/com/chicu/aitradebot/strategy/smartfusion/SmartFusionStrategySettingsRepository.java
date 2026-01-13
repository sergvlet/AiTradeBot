// src/main/java/com/chicu/aitradebot/strategy/smartfusion/SmartFusionStrategySettingsRepository.java
package com.chicu.aitradebot.strategy.smartfusion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SmartFusionStrategySettingsRepository
        extends JpaRepository<SmartFusionStrategySettings, Long> {

    Optional<SmartFusionStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
