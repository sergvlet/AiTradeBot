package com.chicu.aitradebot.strategy.supportresistance;

public interface SupportResistanceStrategySettingsService {

    SupportResistanceStrategySettings getOrCreate(Long chatId);

    SupportResistanceStrategySettings update(Long chatId, SupportResistanceStrategySettings incoming);
}
