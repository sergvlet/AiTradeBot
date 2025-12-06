package com.chicu.aitradebot.engine;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.market.MarketPriceService;
import com.chicu.aitradebot.strategy.core.ContextAwareStrategy;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyEngine {

    private final StrategyEngineRegistry strategyRegistry;
    private final MarketPriceService marketPriceService;

    private ThreadPoolTaskScheduler scheduler;
    private final Map<Long, Map<StrategyType, ScheduledFuture<?>>> running = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(50);
        scheduler.setThreadNamePrefix("STRATEGY-ENGINE-");
        scheduler.initialize();
        log.info("⚙️ StrategyEngine v4.5 initialized (BigDecimal pipeline).");
    }

    // =====================================================================
    // ▶️ START
    // =====================================================================

    public void start(long chatId, StrategyType type, String symbol, int tickIntervalSec) {

        if (isRunning(chatId, type)) {
            log.warn("⚠️ Strategy {} already running for chatId={}.", type, chatId);
            return;
        }

        TradingStrategy strategy = strategyRegistry.getStrategy(type).orElse(null);
        if (strategy == null) {
            log.error("❌ Strategy {} is not registered!", type);
            return;
        }

        if (strategy instanceof ContextAwareStrategy ctx) {
            try {
                ctx.setContext(chatId, symbol);
            } catch (Exception e) {
                log.error("❌ Failed to set context for {}: {}", type, e.getMessage());
            }
        }

        try {
            strategy.start();
        } catch (Exception e) {
            log.error("❌ Strategy {} failed to start: {}", type, e.getMessage());
            return;
        }

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> safeTick(strategy, symbol, chatId, type),
                Duration.ofSeconds(tickIntervalSec)
        );

        running.computeIfAbsent(chatId, k -> new ConcurrentHashMap<>())
                .put(type, future);

        log.info("▶️ Strategy {} STARTED | chatId={} | symbol={} | tick={}s",
                type, chatId, symbol, tickIntervalSec);
    }

    // =====================================================================
    // 🔄 TICK
    // =====================================================================

    private void safeTick(TradingStrategy strategy, String symbol, long chatId, StrategyType type) {
        try {
            if (!strategy.isActive()) {
                log.debug("⏸ Strategy {} inactive → skip tick", type);
                return;
            }

            var priceOpt = marketPriceService.getLastPrice(symbol);
            if (priceOpt.isEmpty()) {
                log.debug("⌛ No price yet for {}", symbol);
                return;
            }

            BigDecimal price = priceOpt.get();
            strategy.onPriceUpdate(symbol, price);

        } catch (Exception e) {
            log.error("❌ Tick error {} | chatId={} | symbol={} → {}",
                    type, chatId, symbol, e.getMessage());
        }
    }

    // =====================================================================
    // ⏹ STOP
    // =====================================================================

    public void stop(long chatId, StrategyType type) {

        Map<StrategyType, ScheduledFuture<?>> map = running.get(chatId);
        if (map == null) {
            log.warn("⚠️ stop() called but no running strategies for chatId={}", chatId);
            return;
        }

        ScheduledFuture<?> future = map.remove(type);
        if (future != null) {
            future.cancel(true);
            log.info("🛑 Future cancelled for {} (chatId={})", type, chatId);
        }

        strategyRegistry.getStrategy(type).ifPresent(str -> {
            try {
                str.stop();
            } catch (Exception e) {
                log.error("❌ Strategy {} stop() failed: {}", type, e.getMessage());
            }
        });

        log.info("⏹ Strategy {} STOPPED (chatId={})", type, chatId);
    }

    // =====================================================================
    // STATUS
    // =====================================================================

    public boolean isRunning(long chatId, StrategyType type) {
        return running.containsKey(chatId)
               && running.get(chatId).containsKey(type);
    }

    public Set<StrategyType> getRunningStrategies(long chatId) {
        return running.containsKey(chatId)
                ? running.get(chatId).keySet()
                : Set.of();
    }
}
