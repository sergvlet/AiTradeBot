package com.chicu.aitradebot.ai.tuning;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Map;

@Builder
public record TuningResult(
        boolean applied,                 // применили ли новые настройки
        String reason,                   // почему не применили / что сделали
        String modelVersion,             // версия модели, если использовалась

        BigDecimal scoreBefore,
        BigDecimal scoreAfter,

        Map<String, Object> oldParams,   // слепок параметров (ключ->значение)
        Map<String, Object> newParams
) {}
