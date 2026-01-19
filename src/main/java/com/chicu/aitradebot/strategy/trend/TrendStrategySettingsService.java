package com.chicu.aitradebot.strategy.trend;

public interface TrendStrategySettingsService {

    TrendStrategySettings getOrCreate(Long chatId);

    TrendStrategySettings update(Long chatId, TrendStrategySettings incoming);
}
