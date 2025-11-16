// src/main/java/com/chicu/aitradebot/strategy/core/RuntimeIntrospectable.java
package com.chicu.aitradebot.strategy.core;

import java.time.Instant;

public interface RuntimeIntrospectable {
    String getSymbol();
    Instant getStartedAt();
    String getLastEvent();
    String getThreadName();
    boolean isActive();
}
