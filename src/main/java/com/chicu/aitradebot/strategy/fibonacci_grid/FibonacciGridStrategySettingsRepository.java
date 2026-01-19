// src/main/java/com/chicu/aitradebot/strategy/fibonacci_grid/FibonacciGridStrategySettingsRepository.java
package com.chicu.aitradebot.strategy.fibonacci_grid;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FibonacciGridStrategySettingsRepository
        extends JpaRepository<FibonacciGridStrategySettings, Long> {

    Optional<FibonacciGridStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
