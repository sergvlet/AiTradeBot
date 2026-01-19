package com.chicu.aitradebot.strategy.fibonacciretrace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FibonacciRetraceStrategySettingsRepository
        extends JpaRepository<FibonacciRetraceStrategySettings, Long> {

    Optional<FibonacciRetraceStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
