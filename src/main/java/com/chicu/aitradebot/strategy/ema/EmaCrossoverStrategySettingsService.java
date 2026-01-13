package com.chicu.aitradebot.strategy.ema;

public interface EmaCrossoverStrategySettingsService {
    EmaCrossoverStrategySettings getOrCreate(Long chatId);
    EmaCrossoverStrategySettings update(Long chatId, EmaCrossoverStrategySettings incoming);

}
