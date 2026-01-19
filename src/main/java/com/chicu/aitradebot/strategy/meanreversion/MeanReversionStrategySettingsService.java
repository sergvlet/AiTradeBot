package com.chicu.aitradebot.strategy.meanreversion;

public interface MeanReversionStrategySettingsService {
    MeanReversionStrategySettings getOrCreate(Long chatId);
}
