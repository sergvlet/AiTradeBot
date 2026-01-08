package com.chicu.aitradebot.ml.runtime;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.ml.tuning.AutoTunerOrchestrator;
import com.chicu.aitradebot.ml.tuning.TuningRequest;
import com.chicu.aitradebot.trade.PositionStore;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlAutoTuneRuntime {

    private final AutoTunerOrchestrator autoTuner;
    private final StrategySettingsService strategySettingsService;
    private final PositionStore positionStore;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> new Thread(r, "ml-autotune"));

    private final Map<String, ScheduledFuture<?>> jobs = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public void onStrategyStarted(Long chatId, StrategyType type, String exchange, NetworkType network) {
        String key = key(chatId, type, exchange, network);

        jobs.computeIfAbsent(key, k ->
                scheduler.scheduleWithFixedDelay(
                        () -> safeTune(chatId, type, exchange, network, "periodic"),
                        Duration.ofMinutes(30).toMillis(),      // первая задержка
                        Duration.ofHours(6).toMillis(),         // период
                        TimeUnit.MILLISECONDS
                )
        );

        // лёгкий warm-up (опционально)
        scheduler.submit(() -> safeTune(chatId, type, exchange, network, "warmup"));
    }

    public void onStrategyStopped(Long chatId, StrategyType type, String exchange, NetworkType network) {
        String key = key(chatId, type, exchange, network);
        ScheduledFuture<?> f = jobs.remove(key);
        if (f != null) f.cancel(false);
    }

    public void onPositionClosed(Long chatId, StrategyType type, String exchange, NetworkType network) {
        scheduler.submit(() -> safeTune(chatId, type, exchange, network, "after-close"));
    }

    private void safeTune(Long chatId, StrategyType type, String exchange, NetworkType network, String reason) {
        String key = key(chatId, type, exchange, network);

        // защита от параллельных запусков
        if (!running.add(key)) return;

        try {
            // НЕ тюним, если стратегия в позиции
            if (positionStore.isInPosition(chatId, type, exchange, network)) {
                log.info("🧠 ML skip tune (in position) chatId={} type={} ex={} net={}", chatId, type, exchange, network);
                return;
            }

            var ss = strategySettingsService.getOrCreate(chatId, type, exchange, network);

            TuningRequest req = TuningRequest.builder()
                    .chatId(chatId)
                    .strategyType(type)
                    .exchange(exchange)
                    .network(network)
                    .symbol(ss.getSymbol())
                    .timeframe(ss.getTimeframe())
                    .candlesLimit(ss.getCachedCandlesLimit())
                    .reason(reason)
                    .build();

            var result = autoTuner.tune(req);

            log.info("🧠 ML tune done chatId={} type={} applied={} reason={}",
                    chatId, type,
                    result != null && result.applied(),
                    result != null ? result.reason() : "null");

        } catch (Exception e) {
            log.error("🧠 ML tune FAILED chatId={} type={} ex={} net={}: {}",
                    chatId, type, exchange, network, e.getMessage(), e);
        } finally {
            running.remove(key);
        }
    }

    private static String key(Long chatId, StrategyType type, String exchange, NetworkType network) {
        return chatId + ":" + type + ":" + exchange + ":" + network;
    }
}
