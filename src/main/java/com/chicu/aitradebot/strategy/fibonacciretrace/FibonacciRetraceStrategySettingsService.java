package com.chicu.aitradebot.strategy.fibonacciretrace;

public interface FibonacciRetraceStrategySettingsService {

    FibonacciRetraceStrategySettings getOrCreate(Long chatId);

    FibonacciRetraceStrategySettings update(Long chatId, FibonacciRetraceStrategySettings incoming);
}
