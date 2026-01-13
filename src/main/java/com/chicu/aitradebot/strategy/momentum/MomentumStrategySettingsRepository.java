package com.chicu.aitradebot.strategy.momentum;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MomentumStrategySettingsRepository
        extends JpaRepository<MomentumStrategySettings, Long> {

    Optional<MomentumStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
