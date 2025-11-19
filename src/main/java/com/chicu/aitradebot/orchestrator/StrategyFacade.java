package com.chicu.aitradebot.orchestrator;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.orchestrator.ai.AiStrategyOrchestrator;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.service.SchedulerService;
import com.chicu.aitradebot.strategy.core.ContextAwareStrategy;
import com.chicu.aitradebot.strategy.core.RuntimeIntrospectable;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.core.StrategySettingsProvider;
import com.chicu.aitradebot.strategy.registry.StrategyRegistry;
import com.chicu.aitradebot.strategy.registry.StrategySettingsMapper;
import com.chicu.aitradebot.strategy.registry.StrategySettingsResolver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyFacade {

    private final StrategyRegistry strategyRegistry;
    private final SchedulerService schedulerService;
    private final StrategySettingsResolver settingsResolver;
    private final ApplicationContext applicationContext;
    private final AiStrategyOrchestrator aiOrchestrator;
    private final OrderService orderService;

    /** Активные экземпляры стратегий: chatId → (strategyType → instance) */
    private final Map<Long, Map<StrategyType, TradingStrategy>> instances = new ConcurrentHashMap<>();

    // =====================================================================
    // 🚀 ЗАПУСК СТРАТЕГИИ
    // =====================================================================
    public void start(long chatId, StrategyType type, String symbol) {

        TradingStrategy strategy = resolveStrategyBean(type);

        instances.computeIfAbsent(chatId, k -> new ConcurrentHashMap<>())
                .put(type, strategy);

        Runnable task = () -> {
            try {

                if (strategy instanceof ContextAwareStrategy ctx) {
                    ctx.setContext(chatId, symbol);
                    log.info("⚙️ Контекст установлен: strategy={}, chatId={}, symbol={}", type, chatId, symbol);
                }

                strategy.start();

                // основной цикл работы стратегии
                while (!Thread.currentThread().isInterrupted() && strategy.isActive()) {
                    Thread.sleep(500L);
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                log.error("❌ Ошибка в стратегии {} (chatId={}): {}", type, chatId, t.getMessage(), t);
            } finally {
                try {
                    strategy.stop();
                } catch (Throwable ignore) {}
                log.info("⏹ Стратегия {} остановлена (chatId={})", type, chatId);
            }
        };

        schedulerService.start(chatId, type, task);
    }

    // =====================================================================
    // 🛑 ОСТАНОВКА
    // =====================================================================
    public void stop(long chatId, StrategyType type) {
        schedulerService.stop(chatId, type);

        var map = instances.get(chatId);
        if (map != null) {
            TradingStrategy s = map.remove(type);
            if (s != null) {
                try {
                    s.stop();
                } catch (Throwable ignore) {}
            }
        }
    }

    public boolean isRunning(long chatId, StrategyType type) {
        return schedulerService.isRunning(chatId, type);
    }

    // =====================================================================
    // 📡 СТАТУС СТРАТЕГИИ
    // =====================================================================
    public StrategyRunInfo status(long chatId, StrategyType type) {

        boolean running = schedulerService.isRunning(chatId, type);

        StrategyRunInfo.StrategyRunInfoBuilder b = StrategyRunInfo.builder()
                .chatId(chatId)
                .type(type)
                .active(running);

        TradingStrategy strategy = instances
                .getOrDefault(chatId, Map.of())
                .get(type);

        // данные из стратегии (runtime)
        if (strategy instanceof RuntimeIntrospectable r) {
            b.symbol(r.getSymbol());
            b.startedAt(r.getStartedAt());
            b.lastEvent(r.getLastEvent());
            b.threadName(r.getThreadName());
        }

        // данные из настроек
        StrategySettingsProvider<?> provider = settingsResolver.getProvider(type);
        if (provider != null) {
            Object settings = provider.load(chatId);
            if (settings != null) {
                StrategySettingsMapper.fill(b, settings);
            }
        }

        return b.build();
    }

    // =====================================================================
    // 📦 ПОЛУЧЕНИЕ БИНА СТРАТЕГИИ
    // =====================================================================
    private TradingStrategy resolveStrategyBean(StrategyType type) {
        Class<? extends TradingStrategy> clazz = strategyRegistry.getStrategyClass(type);
        if (clazz == null) {
            throw new IllegalArgumentException("Strategy not found in registry: " + type);
        }
        return applicationContext.getBean(clazz);
    }

    // =====================================================================
    // 📍 SYMBOL СТРАТЕГИИ
    // =====================================================================
    public String getSymbol(long chatId, StrategyType type) {

        TradingStrategy strategy = instances
                .getOrDefault(chatId, Map.of())
                .get(type);

        if (strategy instanceof RuntimeIntrospectable r) {
            return r.getSymbol();
        }

        return null;
    }

    // =====================================================================
    // 📈 ИСТОРИЯ СДЕЛОК
    // =====================================================================
    public List<OrderEntity> getTrades(long chatId, String symbol) {
        return orderService.getOrderEntitiesByChatIdAndSymbol(chatId, symbol);
    }
}
