package com.chicu.aitradebot.strategy.breakout;

public interface BreakoutStrategySettingsService {

    BreakoutStrategySettings getOrCreate(Long chatId);

    BreakoutStrategySettings update(Long chatId, BreakoutStrategySettings incoming);
}
