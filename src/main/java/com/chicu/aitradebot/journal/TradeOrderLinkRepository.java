package com.chicu.aitradebot.journal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TradeOrderLinkRepository extends JpaRepository<TradeOrderLink, Long> {

    Optional<TradeOrderLink> findByClientOrderId(String clientOrderId);

    List<TradeOrderLink> findByCorrelationIdOrderByCreatedAtAsc(String correlationId);

    List<TradeOrderLink> findTop200ByChatIdOrderByCreatedAtDesc(Long chatId);
}
