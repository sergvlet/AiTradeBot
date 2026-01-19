// src/main/java/com/chicu/aitradebot/strategy/rl/RlDecision.java
package com.chicu.aitradebot.strategy.rl;

import java.math.BigDecimal;

public record RlDecision(
        RlAction action,
        BigDecimal confidence,  // 0..1
        String reason           // для логов/лайва
) {
    public static RlDecision hold(String reason) {
        return new RlDecision(RlAction.HOLD, BigDecimal.ZERO, reason);
    }

    public static RlDecision buy(BigDecimal confidence, String reason) {
        return new RlDecision(RlAction.BUY, nz01(confidence), reason);
    }

    public static RlDecision sell(BigDecimal confidence, String reason) {
        return new RlDecision(RlAction.SELL, nz01(confidence), reason);
    }

    private static BigDecimal nz01(BigDecimal v) {
        if (v == null) return BigDecimal.ZERO;
        if (v.signum() < 0) return BigDecimal.ZERO;
        if (v.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE;
        return v;
    }
}
