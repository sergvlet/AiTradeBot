package com.chicu.aitradebot.strategy.scalping;

import com.chicu.aitradebot.strategy.core.SettingsSnapshot;

public interface ScalpingStrategySettingsService {

    ScalpingStrategySettings getOrCreate(Long chatId);

    ScalpingStrategySettings save(ScalpingStrategySettings settings);

    ScalpingStrategySettings update(Long chatId, ScalpingStrategySettings dto);

    SettingsSnapshot getSnapshot(long chatId);

    ScalpingStrategySettings getEffective(Long chatId);
}
