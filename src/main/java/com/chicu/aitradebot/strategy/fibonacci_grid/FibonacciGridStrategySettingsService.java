// src/main/java/com/chicu/aitradebot/strategy/fibonacci_grid/FibonacciGridStrategySettingsService.java
package com.chicu.aitradebot.strategy.fibonacci_grid;

public interface FibonacciGridStrategySettingsService {

    FibonacciGridStrategySettings getOrCreate(Long chatId);

    FibonacciGridStrategySettings update(Long chatId, FibonacciGridStrategySettings incoming);
}
