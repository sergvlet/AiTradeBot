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

    /**
     * Пул потоков для стратегий.
     * Делается daemon=true чтобы не блокировать завершение приложения.
     */
    private final ScheduledExecutorService executor =
            Executors.newScheduledThreadPool(
                    Runtime.getRuntime().availableProcessors(),
                    r -> {
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        t.setName("StrategyScheduler-" + t.getId());
                        return t;
                    }
            );

    /** key → future задачи */
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    /** key → время старта */
    private final Map<String, Instant> startedAt = new ConcurrentHashMap<>();


    // ==============================================================
    // ▶️ START TASK
    // ==============================================================
    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(String key, Runnable task, long intervalSec) {
        if (intervalSec <= 0) {
            throw new IllegalArgumentException("intervalSec must be > 0");
        }

        // если задача существует — отменяем перед созданием новой
        cancel(key);

        ScheduledFuture<?> future = executor.scheduleAtFixedRate(
                task,
                0,                   // старт немедленно
                intervalSec,
                TimeUnit.SECONDS
        );

        tasks.put(key, future);
        startedAt.put(key, Instant.now());

        log.info("⏱ Scheduler: started '{}' (interval={}s)", key, intervalSec);
        return future;
    }


    // ==============================================================
    // ⏹ CANCEL
    // ==============================================================
    @Override
    public void cancel(String key) {
        ScheduledFuture<?> future = tasks.remove(key);

        if (future != null) {
            future.cancel(false);
            log.info("🛑 Scheduler: cancelled task '{}'", key);
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
    // 🛑 SHUTDOWN
    // ==============================================================
    @PreDestroy
    public void shutdown() {
        if (log.isInfoEnabled()) {
            log.info("💤 SchedulerServiceImpl shutting down…");
        }
        executor.shutdownNow();
    }
}
