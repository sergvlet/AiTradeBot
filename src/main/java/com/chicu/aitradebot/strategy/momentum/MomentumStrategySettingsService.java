package com.chicu.aitradebot.strategy.momentum;

public interface MomentumStrategySettingsService {
    MomentumStrategySettings getOrCreate(Long chatId);
    MomentumStrategySettings save(MomentumStrategySettings s);
    MomentumStrategySettings update(Long chatId, MomentumStrategySettings incoming);

}
