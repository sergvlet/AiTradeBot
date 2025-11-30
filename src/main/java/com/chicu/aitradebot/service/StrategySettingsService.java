package com.chicu.aitradebot.service;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;

import java.util.List;

public interface StrategySettingsService {

    StrategySettings save(StrategySettings settings);
    List<StrategySettings> findAllByChatId(long chatId);
    StrategySettings getSettings(long chatId, StrategyType type);
    StrategySettings getOrCreate(long chatId, StrategyType type);
}
