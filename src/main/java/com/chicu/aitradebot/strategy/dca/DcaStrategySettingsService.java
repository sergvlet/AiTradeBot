// src/main/java/com/chicu/aitradebot/strategy/dca/DcaStrategySettingsService.java
package com.chicu.aitradebot.strategy.dca;

public interface DcaStrategySettingsService {

    DcaStrategySettings getOrCreate(Long chatId);

    DcaStrategySettings update(Long chatId, DcaStrategySettings incoming);
}
