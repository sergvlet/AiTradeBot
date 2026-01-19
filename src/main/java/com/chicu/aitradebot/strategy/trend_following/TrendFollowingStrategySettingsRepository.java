package com.chicu.aitradebot.strategy.trend_following;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TrendFollowingStrategySettingsRepository
        extends JpaRepository<TrendFollowingStrategySettings, Long> {

    Optional<TrendFollowingStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
