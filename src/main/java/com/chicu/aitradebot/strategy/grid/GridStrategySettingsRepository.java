package com.chicu.aitradebot.strategy.grid;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GridStrategySettingsRepository extends JpaRepository<GridStrategySettings, Long> {

    Optional<GridStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
