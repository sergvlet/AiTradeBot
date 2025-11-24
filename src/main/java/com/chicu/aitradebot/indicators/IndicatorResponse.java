package com.chicu.aitradebot.indicators;

import java.util.Map;
import java.util.List;

public record IndicatorResponse(
        Map<String, List<?>> lines,
        Map<String, Object> meta
) {}
