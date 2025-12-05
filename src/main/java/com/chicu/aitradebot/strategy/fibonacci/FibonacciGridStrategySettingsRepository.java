package com.chicu.aitradebot.strategy.fibonacci;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FibonacciGridStrategySettingsRepository
        extends JpaRepository<FibonacciGridStrategySettings, Long> {

    Optional<FibonacciGridStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
