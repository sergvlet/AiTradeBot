package com.chicu.aitradebot.strategy.rsiobos;

public interface RsiObosStrategySettingsService {

    RsiObosStrategySettings getOrCreate(Long chatId);

    RsiObosStrategySettings update(Long chatId, RsiObosStrategySettings incoming);
}
