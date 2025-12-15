package com.chicu.aitradebot.engine;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.registry.StrategyRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyEngineImpl implements StrategyEngine {

    private final StrategyRegistry registry;
    private final SchedulerService scheduler;
    private final StrategySettingsService settingsService;
    private final CandleProvider candleProvider;

    // chatId -> set of running strategies
    private final Map<Long, Set<StrategyType>> running = new ConcurrentHashMap<>();

    private String key(Long chatId, StrategyType type) {
        return chatId + "|" + type.name();
    }

    // ================================================================
    // START
    // ================================================================
    @Override
    public void start(Long chatId, StrategyType type, String symbol, int tickSec) {

        TradingStrategy strategy = registry.getStrategy(type);

        if (strategy == null) {
            log.error("❌ StrategyEngine: Strategy {} not registered", type);
            return;
        }

        // загрузка настроек
        StrategySettings settings = settingsService.getOrCreate(chatId, type);
        String sym = (symbol != null && !symbol.isBlank())
                ? symbol
                : settings.getSymbol();

        if (sym == null || sym.isBlank()) {
            log.error("❌ StrategyEngine: empty symbol for chatId={} type={}", chatId, type);
            return;
        }

        String timeframe = settings.getTimeframe();

        // ==== Запуск стратегии ====
        try {
            log.info("▶ Calling strategy.start(chatId={}, symbol={})", chatId, sym);
            strategy.start(chatId, sym);
        } catch (Throwable t) {
            log.error("❌ Strategy.start() failed: {}", t.getMessage(), t);
        }

        // ==== Планировщик ====
        Runnable task = () -> {
            try {
                var candles = candleProvider.getRecentCandles(chatId, sym, timeframe, 1);
                if (candles.isEmpty()) return;

                var candle = candles.get(0);
                BigDecimal price = BigDecimal.valueOf(candle.getClose());

                strategy.onPriceUpdate(chatId, sym, price, Instant.now());

            } catch (Exception e) {
                log.error("❌ StrategyEngine tick error chatId={} type={}: {}",
                        chatId, type, e.getMessage(), e);
            }
        };

        scheduler.scheduleAtFixedRate(key(chatId, type), task, tickSec);

        running.computeIfAbsent(chatId, k -> ConcurrentHashMap.newKeySet())
                .add(type);

        log.info("🚀 StrategyEngine START chatId={} type={} symbol={} tf={} tick={}s",
                chatId, type, sym, timeframe, tickSec);
    }

    // ================================================================
    // STOP
    // ================================================================
    @Override
    public void stop(Long chatId, StrategyType type) {

        TradingStrategy strategy = registry.getStrategy(type);

        if (strategy != null) {
            try {
                // достаём symbol из настроек
                StrategySettings st = settingsService.getOrCreate(chatId, type);
                String symbol = st.getSymbol();

                strategy.stop(chatId, symbol);

            } catch (Throwable t) {
                log.error("❌ Strategy.stop error: {}", t.getMessage(), t);
            }
        }

        scheduler.cancel(key(chatId, type));

        running.computeIfPresent(chatId, (k, set) -> {
            set.remove(type);
            return set;
        });

        log.info("🛑 StrategyEngine STOP chatId={} type={}", chatId, type);
    }

    // ================================================================
    // STATUS
    // ================================================================
    @Override
    public boolean isRunning(Long chatId, StrategyType type) {
        return running.getOrDefault(chatId, Set.of()).contains(type);
    }

    @Override
    public Set<StrategyType> getRunningStrategies(Long chatId) {
        return Collections.unmodifiableSet(
                running.getOrDefault(chatId, Set.of())
        );
    }
}
