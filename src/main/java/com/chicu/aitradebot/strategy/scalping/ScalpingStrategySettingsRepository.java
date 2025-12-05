package com.chicu.aitradebot.strategy.scalping;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ScalpingStrategySettingsRepository
        extends JpaRepository<ScalpingStrategySettings, Long> {

    Optional<ScalpingStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
