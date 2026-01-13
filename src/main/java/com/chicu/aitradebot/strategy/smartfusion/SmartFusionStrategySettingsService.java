// src/main/java/com/chicu/aitradebot/strategy/smartfusion/SmartFusionStrategySettingsService.java
package com.chicu.aitradebot.strategy.smartfusion;

public interface SmartFusionStrategySettingsService {

    SmartFusionStrategySettings getOrCreate(Long chatId);

    SmartFusionStrategySettings update(Long chatId, SmartFusionStrategySettings incoming);
}
