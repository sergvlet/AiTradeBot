// src/main/java/com/chicu/aitradebot/strategy/ai/MlClassificationStrategySettingsRepository.java
package com.chicu.aitradebot.strategy.ml;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MlClassificationStrategySettingsRepository
        extends JpaRepository<MlClassificationStrategySettings, Long> {

    Optional<MlClassificationStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
