package com.chicu.aitradebot.journal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TradeExecutionEventRepository extends JpaRepository<TradeExecutionEvent, Long> {

    Optional<TradeExecutionEvent> findByEventUid(String eventUid);

    List<TradeExecutionEvent> findByClientOrderIdOrderByEventTimeAsc(String clientOrderId);

    @Query("""
           select distinct e.clientOrderId
           from TradeExecutionEvent e
           where e.createdAt >= :since
             and e.clientOrderId is not null
             and e.clientOrderId <> ''
           """)
    List<String> findDistinctClientOrderIdsSince(@Param("since") Instant since);
}
