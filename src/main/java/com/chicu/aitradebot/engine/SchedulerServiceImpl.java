package com.chicu.aitradebot.engine;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * 🧠 SchedulerServiceImpl (V4-ready)

 * ❗ Назначение:
 *  - единый планировщик стратегий
 *  - НЕ хранит логику стратегии
 *  - НЕ знает про chatId / symbol
 *  - только lifecycle задач
 */
@Slf4j
@Service
public class SchedulerServiceImpl implements SchedulerService {

    /**
     * 🔥 Пул потоков для стратегий
     * daemon=true — не блокирует shutdown приложения
     */
    private final ScheduledExecutorService executor =
            Executors.newScheduledThreadPool(
                    Runtime.getRuntime().availableProcessors(),
                    r -> {
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        t.setName("strategy-scheduler-" + t.getId());
                        return t;
                    }
            );

    /**
     * key → future задачи
     * key формируется ВНЕ (chatId:type:symbol)
     */
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    /**
     * key → время старта
     */
    private final Map<String, Instant> startedAt = new ConcurrentHashMap<>();


    // ==============================================================
    // ▶️ START
    // ==============================================================
    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
            String key,
            Runnable task,
            long intervalSec
    ) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Scheduler key must not be blank");
        }
        if (intervalSec <= 0) {
            throw new IllegalArgumentException("intervalSec must be > 0");
        }

        // если задача уже есть — отменяем
        cancel(key);

        ScheduledFuture<?> future = executor.scheduleAtFixedRate(
                wrapSafe(task, key),
                0,
                intervalSec,
                TimeUnit.SECONDS
        );

        tasks.put(key, future);
        startedAt.put(key, Instant.now());

        log.info("⏱ Scheduler START key='{}' interval={}s", key, intervalSec);
        return future;
    }


    // ==============================================================
    // ⏹ CANCEL
    // ==============================================================
    @Override
    public void cancel(String key) {
        if (key == null) return;

        ScheduledFuture<?> future = tasks.remove(key);
        if (future != null) {
            future.cancel(false);
            log.info("🛑 Scheduler CANCEL key='{}'", key);
        }

        startedAt.remove(key);
    }


    // ==============================================================
    // ℹ STATUS
    // ==============================================================
    @Override
    public boolean isActive(String key) {
        ScheduledFuture<?> future = tasks.get(key);
        return future != null && !future.isCancelled() && !future.isDone();
    }

    @Override
    public Optional<Instant> getStartedAt(String key) {
        return Optional.ofNullable(startedAt.get(key));
    }


    // ==============================================================
    // 🛡 SAFE WRAPPER
    // ==============================================================
    private Runnable wrapSafe(Runnable task, String key) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                // ❗ НИКОГДА не даём scheduler-потоку умереть
                log.error("❌ Scheduler task crashed key='{}'", key, t);
            }
        };
    }


    // ==============================================================
    // 🛑 SHUTDOWN
    // ==============================================================
    @PreDestroy
    public void shutdown() {
        log.info("💤 SchedulerServiceImpl shutdown");
        executor.shutdownNow();
    }
}
