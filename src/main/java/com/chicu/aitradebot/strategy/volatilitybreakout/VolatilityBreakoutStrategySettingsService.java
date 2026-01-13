package com.chicu.aitradebot.strategy.volatilitybreakout;

public interface VolatilityBreakoutStrategySettingsService {
    VolatilityBreakoutStrategySettings getOrCreate(Long chatId);
    VolatilityBreakoutStrategySettings save(VolatilityBreakoutStrategySettings s);
}
