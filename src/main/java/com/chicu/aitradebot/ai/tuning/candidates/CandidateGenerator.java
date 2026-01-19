package com.chicu.aitradebot.ai.tuning.candidates;

import com.chicu.aitradebot.ai.tuning.TuningCandidate;
import com.chicu.aitradebot.ai.tuning.space.ParamSpaceItem;

import java.util.List;
import java.util.Map;

public interface CandidateGenerator {

    /**
     * Генерация N кандидатов по пространству параметров (без хардкода диапазонов).
     */
    List<TuningCandidate> generate(Map<String, ParamSpaceItem> space, int count, long seed);
}
