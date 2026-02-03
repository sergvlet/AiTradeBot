package com.chicu.aitradebot.ai.tuning.eval;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record MlBacktestResult(
        boolean ok,
        String reason,

        int trades,
        BigDecimal profitPct,
        BigDecimal maxDrawdownPct,
        BigDecimal winRatePct,

        double score
) {
    public static MlBacktestResult fail(String reason) {
        return MlBacktestResult.builder()
                .ok(false)
                .reason(reason)
                .trades(0)
                .profitPct(BigDecimal.ZERO)
                .maxDrawdownPct(BigDecimal.ZERO)
                .winRatePct(BigDecimal.ZERO)
                .score(-1.0)
                .build();
    }
}
