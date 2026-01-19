// src/main/java/com/chicu/aitradebot/strategy/rl/RlAgentService.java
package com.chicu.aitradebot.strategy.rl;

public interface RlAgentService {
    RlDecision decide(Long chatId, String symbol, String timeframe, RlState state);
}