package com.chicu.aitradebot.strategy.ema;

public interface EmaCrossoverStrategySettingsService {

    EmaCrossoverStrategySettings getOrCreate(Long chatId);

    EmaCrossoverStrategySettings update(Long chatId, EmaCrossoverStrategySettings incoming);

    default EmaCrossoverStrategySettings save(Long chatId, EmaCrossoverStrategySettings incoming) {
        return update(chatId, incoming);
    }

    Long getVersion(Long chatId);
}
