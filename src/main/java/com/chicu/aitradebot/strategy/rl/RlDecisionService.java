// src/main/java/com/chicu/aitradebot/strategy/rl/RlDecisionService.java
package com.chicu.aitradebot.strategy.rl;

import java.math.BigDecimal;
import java.time.Instant;

public interface RlDecisionService {

    record Decision(
            String action,      // "BUY" | "SELL" | "HOLD"
            double confidence,  // 0..1
            String reason
    ) {}

    Decision decide(
            long chatId,
            String agentKey,
            String symbol,
            String timeframe,
            BigDecimal lastPrice,
            Instant ts
    );
}
