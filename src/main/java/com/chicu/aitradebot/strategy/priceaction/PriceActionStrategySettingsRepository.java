package com.chicu.aitradebot.strategy.priceaction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PriceActionStrategySettingsRepository
        extends JpaRepository<PriceActionStrategySettings, Long> {

    Optional<PriceActionStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
