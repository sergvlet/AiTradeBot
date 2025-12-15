package com.chicu.aitradebot.web.ui;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.web.ui.entity.UiStrategyLayerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface UiStrategyLayerRepository
        extends JpaRepository<UiStrategyLayerEntity, Long> {

    // =====================================================
    // 📊 ДЛЯ ГРАФИКА (FULL SNAPSHOT)
    // =====================================================

    /**
     * Все слои стратегии для графика
     * (используется при первичной загрузке)
     */
    @Query("""
        select l
        from UiStrategyLayerEntity l
        where l.chatId = :chatId
          and l.strategyType = :strategyType
          and l.symbol = :symbol
        order by l.candleTime asc, l.createdAt asc
    """)
    List<UiStrategyLayerEntity> findAllForChart(
            @Param("chatId") Long chatId,
            @Param("strategyType") StrategyType strategyType,
            @Param("symbol") String symbol
    );

    // =====================================================
    // 🔁 REPLAY (ПОСЛЕДНЕЕ СОСТОЯНИЕ)
    // =====================================================

    /**
     * Последние слои определённого типа
     * (LEVELS / ZONE)
     */
    @Query("""
        select l
        from UiStrategyLayerEntity l
        where l.chatId = :chatId
          and l.strategyType = :strategyType
          and l.symbol = :symbol
          and l.layerType = :layerType
        order by l.createdAt desc
    """)
    List<UiStrategyLayerEntity> findLatestByType(
            @Param("chatId") Long chatId,
            @Param("strategyType") StrategyType strategyType,
            @Param("symbol") String symbol,
            @Param("layerType") String layerType
    );

    // =====================================================
    // 🧹 CLEANUP
    // =====================================================

    /**
     * Удалить старые слои (TTL)
     */
    @Transactional
    @Modifying
    @Query("""
        delete from UiStrategyLayerEntity l
        where l.createdAt < :before
    """)
    int deleteOlderThan(@Param("before") Instant before);

    /**
     * Очистить слои стратегии
     * (stop / restart)
     */
    @Transactional
    @Modifying
    @Query("""
        delete from UiStrategyLayerEntity l
        where l.chatId = :chatId
          and l.strategyType = :strategyType
          and l.symbol = :symbol
    """)
    void deleteForStrategy(
            @Param("chatId") Long chatId,
            @Param("strategyType") StrategyType strategyType,
            @Param("symbol") String symbol
    );

    @Modifying
    @Query("""
    delete from UiStrategyLayerEntity l
    where l.chatId = :chatId
      and l.strategyType = :strategyType
      and l.symbol = :symbol
      and l.layerType = :layerType
""")
    void deleteByType(
            @Param("chatId") Long chatId,
            @Param("strategyType") StrategyType strategyType,
            @Param("symbol") String symbol,
            @Param("layerType") String layerType
    );

}
