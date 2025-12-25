package com.chicu.aitradebot.strategy.core.context;

public interface StrategySettingsProvider {

    Object getSnapshot();

    <T> T getSnapshot(Class<T> type);
}
