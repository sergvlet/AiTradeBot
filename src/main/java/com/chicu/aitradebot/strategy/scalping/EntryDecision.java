package com.chicu.aitradebot.strategy.scalping;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public record EntryDecision(
        boolean allowed,
        ScalpingMarketRegime regime,
        ScalpingSetupType setupType,
        BigDecimal score,
        String reason,
        BigDecimal tpPct,
        BigDecimal slPct,
        BigDecimal breakEvenTriggerPct,
        Integer maxHoldSeconds,
        BigDecimal riskScale,
        Map<String, Object> features
) {

    public static EntryDecision allow(ScalpingMarketRegime regime,
                                      ScalpingSetupType setupType,
                                      BigDecimal score,
                                      String reason,
                                      Map<String, Object> features) {
        return new EntryDecision(true, regime, setupType, nz(score), reason, null, null, null, null, null, copy(features));
    }

    public static EntryDecision block(ScalpingMarketRegime regime,
                                      ScalpingSetupType setupType,
                                      String reason,
                                      Map<String, Object> features) {
        return new EntryDecision(false, regime, setupType, BigDecimal.ZERO, reason, null, null, null, null, null, copy(features));
    }

    public EntryDecision withRisk(ScalpingRiskProfile risk) {
        if (risk == null) return this;
        return new EntryDecision(
                allowed, regime, setupType, score, reason,
                risk.tpPct(), risk.slPct(), risk.breakEvenTriggerPct(), risk.maxHoldSec(), risk.riskScale(), features
        );
    }

    public EntryDecision withMlAdjustments(BigDecimal riskScaleMultiplier,
                                           BigDecimal tpMultiplier,
                                           BigDecimal slMultiplier,
                                           String mlReason) {
        BigDecimal nextRisk = multiplyOrKeep(riskScale, riskScaleMultiplier);
        BigDecimal nextTp = multiplyOrKeep(tpPct, tpMultiplier);
        BigDecimal nextSl = multiplyOrKeep(slPct, slMultiplier);
        String nextReason = reason;
        if (mlReason != null && !mlReason.isBlank()) {
            nextReason = (nextReason == null || nextReason.isBlank()) ? mlReason : nextReason + " | " + mlReason;
        }
        return new EntryDecision(allowed, regime, setupType, score, nextReason, nextTp, nextSl, breakEvenTriggerPct, maxHoldSeconds, nextRisk, features);
    }

    private static BigDecimal multiplyOrKeep(BigDecimal value, BigDecimal multiplier) {
        if (value == null || multiplier == null) return value;
        return value.multiply(multiplier);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? Map.of() : new LinkedHashMap<>(source);
    }
}
