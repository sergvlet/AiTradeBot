package com.chicu.aitradebot.strategy.rsie;

public interface RsiEmaStrategySettingsService {

    RsiEmaStrategySettings getOrCreate(Long chatId);

    RsiEmaStrategySettings save(RsiEmaStrategySettings settings);

    RsiEmaStrategySettings update(Long chatId, RsiEmaStrategySettings dto);
}
