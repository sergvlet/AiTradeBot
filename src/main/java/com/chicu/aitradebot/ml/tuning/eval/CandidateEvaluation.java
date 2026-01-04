package com.chicu.aitradebot.ml.tuning.eval;

import com.chicu.aitradebot.ml.tuning.TuningCandidate;
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
