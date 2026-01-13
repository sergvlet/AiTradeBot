// src/main/java/com/chicu/aitradebot/strategy/ml/MlClassificationSettingsRepository.java
package com.chicu.aitradebot.strategy.ml;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MlClassificationSettingsRepository extends JpaRepository<MlClassificationSettings, Long> {

    Optional<MlClassificationSettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
