package com.chicu.aitradebot.strategy.common;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Итоговое решение оркестратора (после голосования стратегий).
 * action: "BUY" | "SELL" | "HOLD"
 * confidence: 0..1
 */
public final class SignalDecision {
    private final String action;
    private final BigDecimal confidence;

    public SignalDecision(String action, BigDecimal confidence) {
        this.action = Objects.requireNonNull(action, "action is required");
        this.confidence = Objects.requireNonNull(confidence, "confidence is required");
    }

    public String action() { return action; }
    public BigDecimal confidence() { return confidence; }

    @Override
    public String toString() {
        return "SignalDecision{action='" + action + "', confidence=" + confidence + '}';
    }
}
