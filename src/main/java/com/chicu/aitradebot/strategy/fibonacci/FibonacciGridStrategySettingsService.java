package com.chicu.aitradebot.strategy.fibonacci;

public interface FibonacciGridStrategySettingsService {

    FibonacciGridStrategySettings getOrCreate(Long chatId);

    FibonacciGridStrategySettings save(FibonacciGridStrategySettings settings);

    FibonacciGridStrategySettings update(Long chatId, FibonacciGridStrategySettings dto);
}
