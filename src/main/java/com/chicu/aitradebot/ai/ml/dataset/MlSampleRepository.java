package com.chicu.aitradebot.ai.ml.dataset;

import com.chicu.aitradebot.common.enums.StrategyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface MlSampleRepository extends JpaRepository<MlSampleEntity, Long> {

    @Query("""
            select s
            from MlSampleEntity s
            where s.chatId = :chatId
              and s.strategyType = :type
              and s.createdAt >= :from
            order by coalesce(s.ts, s.createdAt) desc
            """)
    List<MlSampleEntity> findRecent(@Param("chatId") Long chatId,
                                    @Param("type") StrategyType type,
                                    @Param("from") Instant from);

    /**
     * Новый продовый метод: обучение по контексту стратегии,
     * чтобы не смешивать пользователей / биржи / сети / пары.
     */
    @Query("""
            select s
            from MlSampleEntity s
            where s.strategyType = :type
              and upper(s.symbol) = upper(:symbol)
              and lower(coalesce(s.timeframe, '')) = lower(:timeframe)
              and s.createdAt >= :from
              and (:exchange is null or upper(coalesce(s.exchange, '')) = upper(:exchange))
              and (:network is null or upper(coalesce(s.network, '')) = upper(:network))
            order by coalesce(s.ts, s.createdAt) desc
            """)
    List<MlSampleEntity> findForTrainingByContext(@Param("type") StrategyType type,
                                                  @Param("symbol") String symbol,
                                                  @Param("timeframe") String timeframe,
                                                  @Param("exchange") String exchange,
                                                  @Param("network") String network,
                                                  @Param("from") Instant from);

    /**
     * Обратная совместимость со старым кодом.
     * Старый сервис ожидал метод findForTraining(chatId, type, symbol, timeframe, from, to).
     * chatId и to здесь больше не используются для cohort-train,
     * поэтому делаем безопасный мост на новый метод.
     */
    default List<MlSampleEntity> findForTraining(Long chatId,
                                                 StrategyType type,
                                                 String symbol,
                                                 String timeframe,
                                                 Instant from,
                                                 Instant to) {
        return findForTrainingByContext(type, symbol, timeframe, null, null, from);
    }
}
