package com.chicu.aitradebot.ai.tuning.eval;

import com.chicu.aitradebot.ai.tuning.TuningCandidate;
import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record CandidateEvaluation(
        TuningCandidate candidate,
        BacktestMetrics metrics,
        BigDecimal score,
        String error
) {
    public boolean ok() {
        return error == null || error.isBlank();
    }
}
