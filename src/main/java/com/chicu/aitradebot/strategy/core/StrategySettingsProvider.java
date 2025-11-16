package com.chicu.aitradebot.strategy.core;

public interface StrategySettingsProvider<T> {
    T load(Long chatId);
}
