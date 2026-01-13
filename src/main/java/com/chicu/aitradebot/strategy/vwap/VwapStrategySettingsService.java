// src/main/java/com/chicu/aitradebot/strategy/vwap/VwapStrategySettingsService.java
package com.chicu.aitradebot.strategy.vwap;

public interface VwapStrategySettingsService {

    VwapStrategySettings getOrCreate(Long chatId);

    VwapStrategySettings update(Long chatId, VwapStrategySettings incoming);
}
