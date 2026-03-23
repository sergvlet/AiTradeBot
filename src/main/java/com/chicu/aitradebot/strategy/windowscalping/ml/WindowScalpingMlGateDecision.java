package com.chicu.aitradebot.strategy.windowscalping.ml;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class WindowScalpingMlGateDecision {

    private final boolean approved;

    private final double probability;
    private final double threshold;

    private final double baseThreshold;
    private final double floorThreshold;
    private final double ceilingThreshold;

    private final boolean shouldLog;
    private final boolean thresholdAdjusted;
    private final double previousThreshold;
    private final double newThreshold;

    private final boolean shouldRequestRetrain;
    private final String reason;
}