package com.chicu.aitradebot.ai.ml.dataset;

import com.chicu.aitradebot.common.enums.StrategyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
    List<MlSampleEntity> findRecent(@Param("chatId") Long chatId,
                                    @Param("type") StrategyType type,
                                    @Param("from") Instant from);

    long countByChatIdAndStrategyType(Long chatId, StrategyType type);

    long countByChatIdAndStrategyTypeAndSymbol(Long chatId, StrategyType type, String symbol);

    @Query("""
        select s from MlSampleEntity s
        where s.chatId = :chatId
          and s.strategyType = :type
          and (:symbol is null or s.symbol = :symbol)
          and (:timeframe is null or s.timeframe = :timeframe)
          and (:from is null or s.createdAt >= :from)
          and (:to is null or s.createdAt <= :to)
          and (s.label is not null and s.label <> '')
        order by s.createdAt asc
    """)
    List<MlSampleEntity> findForTraining(@Param("chatId") Long chatId,
                                         @Param("type") StrategyType type,
                                         @Param("symbol") String symbol,
                                         @Param("timeframe") String timeframe,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to);
}