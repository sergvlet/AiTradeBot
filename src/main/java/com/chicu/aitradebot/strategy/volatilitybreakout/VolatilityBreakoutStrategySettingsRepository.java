package com.chicu.aitradebot.strategy.volatilitybreakout;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VolatilityBreakoutStrategySettingsRepository
        extends JpaRepository<VolatilityBreakoutStrategySettings, Long> {

    Optional<VolatilityBreakoutStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
