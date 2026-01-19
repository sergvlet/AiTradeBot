// src/main/java/com/chicu/aitradebot/strategy/rl/RlAgentStrategySettingsRepository.java
package com.chicu.aitradebot.strategy.rl;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RlAgentStrategySettingsRepository extends JpaRepository<RlAgentStrategySettings, Long> {
    Optional<RlAgentStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
