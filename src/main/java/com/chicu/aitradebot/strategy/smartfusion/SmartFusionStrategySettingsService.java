package com.chicu.aitradebot.strategy.smartfusion;

import com.chicu.aitradebot.strategy.smartfusion.dto.SmartFusionUserSettingsDto;

import java.util.Optional;

public interface SmartFusionStrategySettingsService {

    SmartFusionStrategySettings getOrCreate(Long chatId, String symbol);

    SmartFusionStrategySettings save(SmartFusionStrategySettings settings);

    SmartFusionStrategySettings updateUserParams(Long chatId, SmartFusionUserSettingsDto dto);

    Optional<SmartFusionStrategySettings> findByChatId(Long chatId);

    Optional<SmartFusionStrategySettings> findByChatIdAndSymbol(Long chatId, String symbol);
}
