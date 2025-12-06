package com.chicu.aitradebot.strategy.scalping;

public interface ScalpingStrategySettingsService {

    ScalpingStrategySettings getOrCreate(Long chatId);

    ScalpingStrategySettings save(ScalpingStrategySettings settings);

    ScalpingStrategySettings update(Long chatId, ScalpingStrategySettings dto);
}
