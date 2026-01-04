package com.chicu.aitradebot.ml.tuning.guard;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.ml.tuning.TuningCandidate;

import java.util.Map;

public interface TuningGuard {

    StrategyType getStrategyType();

    /**
     * Проверка кандидата относительно текущих параметров.
     * currentParams и candidate.params() — плоские map (ключ->значение).
     */
    GuardDecision checkCandidate(Long chatId, Map<String, Object> currentParams, TuningCandidate candidate);

    /**
     * Проверка частоты тюнинга (на основе истории).
     */
    GuardDecision checkFrequency(Long chatId);
}
