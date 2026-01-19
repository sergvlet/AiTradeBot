package com.chicu.aitradebot.ai.tuning.guard;

import lombok.Builder;

@Builder
public record GuardDecision(
        boolean allowed,
        String reason
) {
    public static GuardDecision allow() {
        return GuardDecision.builder().allowed(true).reason("OK").build();
    }

    public static GuardDecision deny(String reason) {
        return GuardDecision.builder().allowed(false).reason(reason).build();
    }
}
