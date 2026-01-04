package com.chicu.aitradebot.ml.tuning.scalping;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.ml.tuning.TuningCandidate;
import com.chicu.aitradebot.ml.tuning.TuningRequest;
import com.chicu.aitradebot.ml.tuning.eval.BacktestMetrics;
import com.chicu.aitradebot.ml.tuning.eval.BacktestPort;
import com.chicu.aitradebot.ml.tuning.eval.CandidateEvaluation;
import com.chicu.aitradebot.ml.tuning.score.TuningScorePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScalpingCandidateEvaluator {

    private final BacktestPort backtestPort;

    @Qualifier("scalpingScorePolicy")
    private final TuningScorePolicy scorePolicy;

    public List<CandidateEvaluation> evaluateBatch(TuningRequest req, List<TuningCandidate> candidates, int maxCandidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();

        int limit = Math.max(1, Math.min(maxCandidates, candidates.size()));
        List<CandidateEvaluation> out = new ArrayList<>(limit);

        for (int i = 0; i < limit; i++) {
            TuningCandidate c = candidates.get(i);
            try {
                BacktestMetrics m = backtestPort.backtest(
                        req.chatId(),
                        StrategyType.SCALPING,
                        req.symbol(),
                        req.timeframe(),
                        c.params(),
                        req.startAt(),
                        req.endAt()
                );

                BigDecimal score = scorePolicy.score(m);

                out.add(CandidateEvaluation.builder()
                        .candidate(c)
                        .metrics(m)
                        .score(score)
                        .build());

            } catch (Exception ex) {
                out.add(CandidateEvaluation.builder()
                        .candidate(c)
                        .error(ex.getClass().getSimpleName() + ": " + ex.getMessage())
                        .build());
            }
        }

        out.sort(Comparator.comparing(CandidateEvaluation::score, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return out;
    }
}
