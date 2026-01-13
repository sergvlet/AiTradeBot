package com.chicu.aitradebot.strategy.ema;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmaCrossoverStrategySettingsRepository
        extends JpaRepository<EmaCrossoverStrategySettings, Long> {

    Optional<EmaCrossoverStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
