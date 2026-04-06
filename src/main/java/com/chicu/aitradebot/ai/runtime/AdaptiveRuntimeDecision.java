package com.chicu.aitradebot.ai.runtime;

import java.time.Duration;

public record AdaptiveRuntimeDecision(
        String action,
        String reason,
        boolean triggerTune,
        boolean triggerTrain,
        Duration tuneDebounce,
        Duration trainDebounce
) {

    public static AdaptiveRuntimeDecision none(String reason) {
        return new AdaptiveRuntimeDecision("NONE", reason, false, false, Duration.ZERO, Duration.ZERO);
    }

    public static AdaptiveRuntimeDecision tune(String action, String reason, Duration tuneDebounce) {
        return new AdaptiveRuntimeDecision(action, reason, true, false, tuneDebounce, Duration.ZERO);
    }

    public static AdaptiveRuntimeDecision trainAndTune(String action,
                                                       String reason,
                                                       Duration trainDebounce,
                                                       Duration tuneDebounce) {
        return new AdaptiveRuntimeDecision(action, reason, true, true, tuneDebounce, trainDebounce);
    }

    public boolean isNoop() {
        return !triggerTune && !triggerTrain;
    }
}
