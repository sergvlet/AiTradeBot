package com.chicu.aitradebot.strategy.rsie;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RsiEmaStrategySettingsRepository
        extends JpaRepository<RsiEmaStrategySettings, Long> {

    Optional<RsiEmaStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
