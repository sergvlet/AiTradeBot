package com.chicu.aitradebot.engine;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

public interface SchedulerService {

    /**
     * Запускает периодическую задачу.
     *
     * @param key         уникальный ключ (например, "chatId|SCALPING")
     * @param task        логика стратегии (onPriceUpdate и т.п.)
     * @param intervalSec интервал между тиками
     */
    ScheduledFuture<?> scheduleAtFixedRate(String key, Runnable task, long intervalSec);

    /** Остановка задачи по ключу. */
    void cancel(String key);

    /** Есть ли активная задача по ключу. */
    boolean isActive(String key);

    /** Время старта задачи. */
    Optional<Instant> getStartedAt(String key);
}
