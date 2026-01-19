// src/main/java/com/chicu/aitradebot/strategy/volume/VolumeProfileStrategySettingsRepository.java
package com.chicu.aitradebot.strategy.volume;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VolumeProfileStrategySettingsRepository
        extends JpaRepository<VolumeProfileStrategySettings, Long> {

    Optional<VolumeProfileStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
