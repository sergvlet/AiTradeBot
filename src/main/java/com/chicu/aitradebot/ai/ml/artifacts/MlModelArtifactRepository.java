package com.chicu.aitradebot.ai.ml.artifacts;

import com.chicu.aitradebot.common.enums.StrategyType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MlModelArtifactRepository extends JpaRepository<MlModelArtifactEntity, Long> {

    Optional<MlModelArtifactEntity> findTopByChatIdAndStrategyTypeOrderByCreatedAtDesc(Long chatId, StrategyType type);

    default MlModelArtifactEntity findLatest(Long chatId, StrategyType type) {
        return findTopByChatIdAndStrategyTypeOrderByCreatedAtDesc(chatId, type).orElse(null);
    }
}