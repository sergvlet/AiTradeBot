package com.chicu.aitradebot.ml.tuning.space;

import com.chicu.aitradebot.ml.persistence.ParamValueType;
import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record ParamSpaceItem(
        String name,
        ParamValueType type,
        BigDecimal min,
        BigDecimal max,
        BigDecimal step
) {}
