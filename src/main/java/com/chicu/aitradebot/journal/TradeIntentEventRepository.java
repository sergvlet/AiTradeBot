package com.chicu.aitradebot.journal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TradeIntentEventRepository extends JpaRepository<TradeIntentEvent, Long> {

    Optional<TradeIntentEvent> findByCorrelationId(String correlationId);

    // ✅ нужно для reconcile (берём последние intent, где уже известен clientOrderId)
    List<TradeIntentEvent> findTop200ByClientOrderIdIsNotNullOrderByCreatedAtDesc();

    // ✅ удобно, когда reconcile идёт по одному clientOrderId
    Optional<TradeIntentEvent> findTop1ByClientOrderIdOrderByCreatedAtDesc(String clientOrderId);

    List<TradeIntentEvent> findTop200ByChatIdAndStrategyTypeAndExchangeNameAndNetworkTypeOrderByCreatedAtDesc(
            Long chatId,
            com.chicu.aitradebot.common.enums.StrategyType strategyType,
            String exchangeName,
            com.chicu.aitradebot.common.enums.NetworkType networkType
    );

    @Query("""
           select e
           from TradeIntentEvent e
           where e.createdAt >= :fromTs
             and e.createdAt < :toTs
             and e.chatId = :chatId
             and e.strategyType = :strategyType
             and e.exchangeName = :exchangeName
             and e.networkType = :networkType
           order by e.createdAt asc
           """)
    List<TradeIntentEvent> findForWindow(
            @Param("chatId") Long chatId,
            @Param("strategyType") com.chicu.aitradebot.common.enums.StrategyType strategyType,
            @Param("exchangeName") String exchangeName,
            @Param("networkType") com.chicu.aitradebot.common.enums.NetworkType networkType,
            @Param("fromTs") Instant fromTs,
            @Param("toTs") Instant toTs
    );
}
