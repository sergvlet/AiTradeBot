package com.chicu.aitradebot.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

@Slf4j
@Service
public class SchedulerServiceImpl implements SchedulerService {

    private final ScheduledExecutorService executor =
            Executors.newScheduledThreadPool(8); // можно вынести в настройки

    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private final Map<String, Instant> startedAt = new ConcurrentHashMap<>();

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(String key, Runnable task, long intervalSec) {
        cancel(key); // на всякий случай останавливаем старую

        ScheduledFuture<?> future = executor.scheduleAtFixedRate(
                task,
                0,
                intervalSec,
                TimeUnit.SECONDS
        );
        tasks.put(key, future);
        startedAt.put(key, Instant.now());

        log.info("⏱ Запущен scheduler task '{}' с интервалом {} сек", key, intervalSec);
        return future;
    }

    @Override
    public void cancel(String key) {
        ScheduledFuture<?> future = tasks.remove(key);
        if (future != null) {
            future.cancel(false);
            startedAt.remove(key);
            log.info("🛑 Остановлен scheduler task '{}'", key);
        }
    }

    @Override
    public boolean isActive(String key) {
        ScheduledFuture<?> future = tasks.get(key);
        return future != null && !future.isCancelled() && !future.isDone();
    }

    @Override
    public Optional<Instant> getStartedAt(String key) {
        return Optional.ofNullable(startedAt.get(key));
    }

    @PreDestroy
    public void shutdown() {
        log.info("💤 Останавливаем SchedulerServiceImpl…");
        executor.shutdownNow();
    }
}
