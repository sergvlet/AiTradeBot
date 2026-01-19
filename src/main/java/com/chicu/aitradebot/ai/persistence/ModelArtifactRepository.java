package com.chicu.aitradebot.ai.persistence;

import com.chicu.aitradebot.common.enums.StrategyType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelArtifactRepository extends JpaRepository<ModelArtifactEntity, Long> {

    Optional<ModelArtifactEntity> findTopByStrategyTypeOrderByCreatedAtDesc(StrategyType strategyType);

    Optional<ModelArtifactEntity> findByStrategyTypeAndVersion(StrategyType strategyType, String version);

    List<ModelArtifactEntity> findTop30ByStrategyTypeOrderByCreatedAtDesc(StrategyType strategyType);

    boolean existsByStrategyTypeAndVersion(StrategyType strategyType, String version);
}
