// src/main/java/com/chicu/aitradebot/strategy/rl/RlAgentStrategySettingsService.java
package com.chicu.aitradebot.strategy.rl;

public interface RlAgentStrategySettingsService {
    RlAgentStrategySettings getOrCreate(Long chatId);
}
