package com.chicu.aitradebot.strategy.breakout;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BreakoutStrategySettingsRepository
        extends JpaRepository<BreakoutStrategySettings, Long> {

    Optional<BreakoutStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
