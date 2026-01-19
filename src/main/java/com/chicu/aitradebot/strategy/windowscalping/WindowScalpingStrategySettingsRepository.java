package com.chicu.aitradebot.strategy.windowscalping;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WindowScalpingStrategySettingsRepository
        extends JpaRepository<WindowScalpingStrategySettings, Long> {

    Optional<WindowScalpingStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
