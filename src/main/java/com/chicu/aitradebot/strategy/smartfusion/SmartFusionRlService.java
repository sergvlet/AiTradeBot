// src/main/java/com/chicu/aitradebot/strategy/smartfusion/SmartFusionRlService.java
package com.chicu.aitradebot.strategy.smartfusion;

public interface SmartFusionRlService {

    record RlDecision(String action, double confidence) {} // action: BUY/SELL/HOLD

    RlDecision decide(Long chatId,
                      String agentKey,
                      String symbol,
                      String timeframe,
                      SmartFusionFeatures features);
}
