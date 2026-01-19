package com.chicu.aitradebot.journal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TradeOutcomeRepository extends JpaRepository<TradeOutcome, Long> {

    Optional<TradeOutcome> findByCorrelationId(String correlationId);

    boolean existsByCorrelationId(String correlationId);
}
