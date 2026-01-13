// src/main/java/com/chicu/aitradebot/strategy/hybrid/HybridStrategySettingsService.java
package com.chicu.aitradebot.strategy.hybrid;

public interface HybridStrategySettingsService {

    HybridStrategySettings getOrCreate(Long chatId);

    HybridStrategySettings update(Long chatId, HybridStrategySettings incoming);
}
