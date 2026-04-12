package com.chicu.aitradebot.strategy.scalping;

import java.math.BigDecimal;

public record ScalpingRiskProfile(
        BigDecimal tpPct,
        BigDecimal slPct,
        BigDecimal breakEvenTriggerPct,
        Integer maxHoldSec,
        BigDecimal riskScale
) {
}
