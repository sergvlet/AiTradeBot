package com.chicu.aitradebot.engine;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

/**
 * Чистый планировщик задач по строковому ключу.
 *
 * Задача этого сервиса — только крутить Runnable по таймеру.
 * Он НЕ знает ни про стратегии, ни про chatId, ни про символы.
 */
public interface SchedulerService {

    /**
     * Запускает периодическую задачу.
     *
     * @param key         уникальный ключ задачи (например: "12345|SCALPING")
     * @param task        логика, которую надо регулярно выполнять
     * @param intervalSec интервал между тиками, в секундах
     */
    ScheduledFuture<?> scheduleAtFixedRate(String key, Runnable task, long intervalSec);

    /**
     * Остановка задачи по ключу.
     */
    void cancel(String key);

    /**
     * Активна ли задача по ключу.
     */
    boolean isActive(String key);

    /**
     * Время старта задачи по ключу.
     */
    Optional<Instant> getStartedAt(String key);
}
