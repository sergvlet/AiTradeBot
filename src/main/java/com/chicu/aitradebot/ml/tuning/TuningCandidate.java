package com.chicu.aitradebot.ml.tuning;

import lombok.Builder;

import java.util.Map;

/**
 * Универсальный контейнер параметров для любой стратегии.
 * Никаких диапазонов/шагов тут нет — только конкретные значения кандидата.
 */
@Builder
public record TuningCandidate(
        Map<String, Object> params
) {}
