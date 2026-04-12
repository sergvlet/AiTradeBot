// src/main/java/com/chicu/aitradebot/web/ui/UiStrategyLayerRepository.java
package com.chicu.aitradebot.web.ui;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.web.ui.entity.UiStrategyLayerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface UiStrategyLayerRepository extends JpaRepository<UiStrategyLayerEntity, Long> {

    List<UiStrategyLayerEntity> findByChatIdAndStrategyTypeAndSymbolOrderByCandleTimeAsc(
            Long chatId,
            StrategyType strategyType,
            String symbol
    );

    Optional<UiStrategyLayerEntity> findTop1ByChatIdAndStrategyTypeAndSymbolAndLayerTypeOrderByCreatedAtDesc(
            Long chatId,
            StrategyType strategyType,
            String symbol,
            String layerType
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
           delete from UiStrategyLayerEntity e
           where e.chatId = :chatId
             and e.strategyType = :strategyType
             and e.symbol = :symbol
             and e.layerType = :layerType
           """)
    int deleteByType(@Param("chatId") Long chatId,
                     @Param("strategyType") StrategyType strategyType,
                     @Param("symbol") String symbol,
                     @Param("layerType") String layerType);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
           delete from UiStrategyLayerEntity e
           where e.chatId = :chatId
             and e.strategyType = :strategyType
             and e.symbol = :symbol
           """)
    int deleteAllByContext(@Param("chatId") Long chatId,
                           @Param("strategyType") StrategyType strategyType,
                           @Param("symbol") String symbol);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
           delete from UiStrategyLayerEntity e
           where e.createdAt < :olderThan
           """)
    int deleteOlderThan(@Param("olderThan") Instant olderThan);
}