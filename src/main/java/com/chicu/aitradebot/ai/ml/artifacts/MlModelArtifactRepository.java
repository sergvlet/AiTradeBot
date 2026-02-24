package com.chicu.aitradebot.ai.ml.artifacts;

import com.chicu.aitradebot.common.enums.StrategyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MlModelArtifactRepository extends JpaRepository<MlModelArtifactEntity, Long> {

    @Query("""
        select a from MlModelArtifactEntity a
        where a.chatId=:chatId and a.strategyType=:type
        order by a.createdAt desc
        limit 1
    """)
    MlModelArtifactEntity findLatest(Long chatId, StrategyType type);
}
