package com.chicu.aitradebot.strategy.supportresistance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SupportResistanceStrategySettingsRepository
        extends JpaRepository<SupportResistanceStrategySettings, Long> {

    Optional<SupportResistanceStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
