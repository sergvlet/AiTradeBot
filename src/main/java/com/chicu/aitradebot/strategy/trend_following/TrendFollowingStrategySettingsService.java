package com.chicu.aitradebot.strategy.trend_following;

public interface TrendFollowingStrategySettingsService {
    TrendFollowingStrategySettings getOrCreate(Long chatId);
    TrendFollowingStrategySettings update(Long chatId, TrendFollowingStrategySettings incoming);

}
