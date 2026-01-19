package com.chicu.aitradebot.strategy.vwap;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VwapStrategySettingsRepository extends JpaRepository<VwapStrategySettings, Long> {

    Optional<VwapStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
