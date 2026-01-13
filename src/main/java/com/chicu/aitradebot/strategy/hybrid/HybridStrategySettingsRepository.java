// src/main/java/com/chicu/aitradebot/strategy/hybrid/HybridStrategySettingsRepository.java
package com.chicu.aitradebot.strategy.hybrid;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HybridStrategySettingsRepository extends JpaRepository<HybridStrategySettings, Long> {
    Optional<HybridStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
