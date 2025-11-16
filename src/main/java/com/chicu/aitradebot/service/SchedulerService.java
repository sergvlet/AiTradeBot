package com.chicu.aitradebot.service;

import com.chicu.aitradebot.common.enums.StrategyType;

/**
 * Управляет фоновыми задачами стратегий:
 * запуск, остановка и проверка активности.
 */
public interface SchedulerService {

    /** Запускает стратегию в отдельном потоке */
    void start(long chatId, StrategyType type, Runnable task);

    /** Останавливает стратегию */
    void stop(long chatId, StrategyType type);

    /** Проверяет, активен ли поток стратегии */
    boolean isRunning(long chatId, StrategyType type);
}
