package com.chicu.aitradebot.ai.tuning.space;

import com.chicu.aitradebot.ai.persistence.ParamValueType;
import com.chicu.aitradebot.ai.persistence.TuningSpaceEntity;

import java.math.BigDecimal;

public final class ParamSpaceValidator {

    private ParamSpaceValidator() {}

    public static void validateOrThrow(TuningSpaceEntity e) {
        if (e == null) throw new IllegalArgumentException("ParamSpace: entity is null");
        if (e.getParamName() == null || e.getParamName().trim().isEmpty()) {
            throw new IllegalArgumentException("ParamSpace: paramName пустой");
        }
        if (e.getValueType() == null) {
            throw new IllegalArgumentException("ParamSpace: valueType не задан для " + e.getParamName());
        }

        ParamValueType t = e.getValueType();

        // BOOLEAN/STRING: диапазоны могут быть null (на будущее).
        if (t == ParamValueType.BOOLEAN || t == ParamValueType.STRING) {
            return;
        }

        BigDecimal min = e.getMinValue();
        BigDecimal max = e.getMaxValue();
        BigDecimal step = e.getStepValue();

        if (min == null || max == null || step == null) {
            throw new IllegalArgumentException("ParamSpace: min/max/step должны быть заданы для " + e.getParamName());
        }
        if (min.compareTo(max) > 0) {
            throw new IllegalArgumentException("ParamSpace: min > max для " + e.getParamName());
        }
        if (step.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("ParamSpace: step <= 0 для " + e.getParamName());
        }

        // Для INT — step должен быть целым и min/max целыми (по смыслу).
        if (t == ParamValueType.INT) {
            if (min.stripTrailingZeros().scale() > 0 || max.stripTrailingZeros().scale() > 0 || step.stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException("ParamSpace: INT параметр требует целые min/max/step: " + e.getParamName());
            }
        }
    }
}
