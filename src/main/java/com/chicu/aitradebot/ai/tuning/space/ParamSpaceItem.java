package com.chicu.aitradebot.ai.tuning.space;

import com.chicu.aitradebot.ai.persistence.ParamValueType;
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
