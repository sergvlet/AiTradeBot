package com.chicu.aitradebot.strategy.rsiobos;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RsiObosStrategySettingsRepository
        extends JpaRepository<RsiObosStrategySettings, Long> {

    Optional<RsiObosStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
