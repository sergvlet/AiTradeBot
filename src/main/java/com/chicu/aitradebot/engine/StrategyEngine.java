package com.chicu.aitradebot.engine;

import com.chicu.aitradebot.common.enums.StrategyType;

import java.util.Set;

/**
 * Ядро управления стратегиями:
 *  - запуск/остановка для chatId + StrategyType
 *  - учёт запущенных стратегий
 */
public interface StrategyEngine {

    void start(Long chatId, StrategyType type, String symbol, int tickSec);

    void stop(Long chatId, StrategyType type);

    boolean isRunning(Long chatId, StrategyType type);

    Set<StrategyType> getRunningStrategies(Long chatId);
}
