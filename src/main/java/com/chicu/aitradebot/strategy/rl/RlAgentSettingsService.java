// src/main/java/com/chicu/aitradebot/strategy/rl/RlAgentSettingsService.java
package com.chicu.aitradebot.strategy.rl;

public interface RlAgentSettingsService {

    RlAgentSettings getOrCreate(Long chatId);

    RlAgentSettings save(RlAgentSettings s);
}
