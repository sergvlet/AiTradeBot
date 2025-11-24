package com.chicu.aitradebot.service;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;

public interface StrategySettingsService {

    StrategySettings save(StrategySettings settings);

    StrategySettings getSettings(long chatId, StrategyType type);

    StrategySettings getOrCreate(long chatId, StrategyType type);
}
