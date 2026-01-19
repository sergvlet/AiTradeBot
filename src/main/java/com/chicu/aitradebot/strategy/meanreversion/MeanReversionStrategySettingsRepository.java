package com.chicu.aitradebot.strategy.meanreversion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MeanReversionStrategySettingsRepository
        extends JpaRepository<MeanReversionStrategySettings, Long> {

    Optional<MeanReversionStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
