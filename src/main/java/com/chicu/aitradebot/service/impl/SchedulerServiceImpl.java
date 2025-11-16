package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.service.SchedulerService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class SchedulerServiceImpl implements SchedulerService {

    /**
     * Собственный ThreadFactory с счётчиком — без использования Thread.getId().
     * Имена вида: strategy-exec-1, strategy-exec-2, ...
     */
    private static final class StrategyThreadFactory implements ThreadFactory {
        private final AtomicLong ctr = new AtomicLong(1);
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setName("strategy-exec-" + ctr.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }

    /** Пул потоков под стратегии */
    private final ExecutorService executor = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors(),
            Math.max(4, Runtime.getRuntime().availableProcessors() * 2),
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new StrategyThreadFactory()
    );

    /** Запущенные задачи: chatId -> (StrategyType -> Future) */
    private final ConcurrentMap<Long, ConcurrentMap<StrategyType, Future<?>>> runningTasks = new ConcurrentHashMap<>();

    @Override
    public void start(long chatId, StrategyType type, Runnable task) {
        Objects.requireNonNull(task, "task must not be null");

        var userMap = runningTasks.computeIfAbsent(chatId, k -> new ConcurrentHashMap<>());

        // если уже есть — аккуратно остановим предыдущую
        var old = userMap.remove(type);
        if (old != null) {
            old.cancel(true);
        }

        // ссылка на текущий future внутри замыкания
        final AtomicReference<Future<?>> ref = new AtomicReference<>();

        Runnable wrapped = () -> {
            final String threadName = Thread.currentThread().getName();
            log.info("▶️ Запуск стратегии {} (chatId={}) в потоке {}", type, chatId, threadName);
            try {
                task.run();
            } catch (CancellationException ex) {
                log.info("⏹ Стратегия {} (chatId={}) отменена", type, chatId);
            } catch (Throwable t) {
                log.error("❗ Ошибка в задаче стратегии {} (chatId={}): {}", type, chatId, t.getMessage(), t);
            } finally {
                try {
                    var f = ref.get();
                    var map = runningTasks.get(chatId);
                    if (map != null) {
                        // удаляем только если тот же самый future (защита от гонок)
                        map.remove(type, f);
                        if (map.isEmpty()) {
                            runningTasks.remove(chatId, map);
                        }
                    }
                } finally {
                    log.info("■ Стратегия {} (chatId={}) завершена (поток: {})", type, chatId, threadName);
                }
            }
        };

        Future<?> future = executor.submit(wrapped);
        ref.set(future);
        userMap.put(type, future);
    }

    @Override
    public void stop(long chatId, StrategyType type) {
        var userMap = runningTasks.get(chatId);
        if (userMap == null) return;

        var future = userMap.remove(type);
        if (future != null) {
            future.cancel(true);
            log.info("⏹ Остановлена фоновая задача стратегии {} (chatId={})", type, chatId);
        }
        if (userMap.isEmpty()) {
            runningTasks.remove(chatId, userMap);
        }
    }

    @Override
    public boolean isRunning(long chatId, StrategyType type) {
        var userMap = runningTasks.get(chatId);
        if (userMap == null) return false;
        var f = userMap.get(type);
        return f != null && !f.isDone() && !f.isCancelled();
    }

    @PreDestroy
    public void shutdown() {
        log.info("🧹 Завершение всех фоновых задач стратегий...");
        try {
            for (Map.Entry<Long, ConcurrentMap<StrategyType, Future<?>>> entry : runningTasks.entrySet()) {
                for (Future<?> f : entry.getValue().values()) {
                    f.cancel(true);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
