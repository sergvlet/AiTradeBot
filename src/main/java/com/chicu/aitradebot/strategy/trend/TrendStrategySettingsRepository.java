// src/main/java/com/chicu/aitradebot/strategy/trend/TrendStrategySettingsRepository.java
package com.chicu.aitradebot.strategy.trend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TrendStrategySettingsRepository extends JpaRepository<TrendStrategySettings, Long> {

    Optional<TrendStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
