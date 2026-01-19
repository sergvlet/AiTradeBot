package com.chicu.aitradebot.ai.override;

import com.chicu.aitradebot.common.enums.StrategyType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiOverrideRepository extends JpaRepository<AiOverrideEntity, Long> {

    Optional<AiOverrideEntity> findTopByChatIdAndStrategyTypeAndActiveTrueOrderByCreatedAtDesc(
            Long chatId,
            StrategyType strategyType
    );
}
