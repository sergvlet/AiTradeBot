// src/main/java/com/chicu/aitradebot/strategy/rl/RlAgentSettingsRepository.java
package com.chicu.aitradebot.strategy.rl;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RlAgentSettingsRepository extends JpaRepository<RlAgentSettings, Long> {

    Optional<RlAgentSettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
