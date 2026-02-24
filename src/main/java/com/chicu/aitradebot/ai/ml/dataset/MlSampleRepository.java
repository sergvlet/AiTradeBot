package com.chicu.aitradebot.ai.ml.dataset;

import com.chicu.aitradebot.common.enums.StrategyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface MlSampleRepository extends JpaRepository<MlSampleEntity, Long> {

    @Query("""
        select s from MlSampleEntity s
        where s.chatId = :chatId
          and s.strategyType = :type
          and s.createdAt >= :from
        order by s.createdAt desc
    """)
    List<MlSampleEntity> findRecent(Long chatId, StrategyType type, Instant from);

    long countByChatIdAndStrategyType(Long chatId, StrategyType type);
}
