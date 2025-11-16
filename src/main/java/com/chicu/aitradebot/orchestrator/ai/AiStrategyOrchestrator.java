package com.chicu.aitradebot.orchestrator.ai;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.registry.StrategyRegistry;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiStrategyOrchestrator {

    private final StrategyRegistry strategyRegistry;
    private final OrderService orderService;

    private final Map<Long, Set<StrategyType>> activeStrategies = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> activeOrchestrators = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("🧠 AiStrategyOrchestrator готов.");
    }

    // ===================== Управление =====================

    public void startSession(Long chatId) {
        if (Boolean.TRUE.equals(activeOrchestrators.get(chatId))) return;

        activeOrchestrators.put(chatId, true);
        activeStrategies.putIfAbsent(chatId, new HashSet<>(strategyRegistry.getAll().keySet()));

        runTick(chatId);
    }

    public void stopSession(Long chatId) {
        activeOrchestrators.remove(chatId);
        activeStrategies.remove(chatId);
    }

    public boolean isActive(Long chatId) {
        return Boolean.TRUE.equals(activeOrchestrators.get(chatId));
    }

    // ===================== Стратегии =====================

    public void activateStrategy(Long chatId, StrategyType type) {
        activeStrategies.computeIfAbsent(chatId, k -> new HashSet<>()).add(type);
    }

    public void deactivateStrategy(Long chatId, StrategyType type) {
        Optional.ofNullable(activeStrategies.get(chatId)).ifPresent(s -> s.remove(type));
    }

    // ===================== Основной цикл =====================

    public void runTick(Long chatId) {
        if (!isActive(chatId)) return;

        Set<StrategyType> strategies = activeStrategies.getOrDefault(chatId, Collections.emptySet());
        if (strategies.isEmpty()) return;

        List<String> signals = new ArrayList<>();

        for (StrategyType type : strategies) {
            try {
                TradingStrategy strategy = strategyRegistry.newInstance(type);
                if (strategy == null) continue;

                String signal = "HOLD";

                try {
                    var m = strategy.getClass().getMethod("getLastEvent");
                    signal = (String) m.invoke(strategy);
                } catch (Exception ignored) {}

                log.info("📊 {} -> {}", type, signal);
                signals.add(signal);

            } catch (Exception e) {
                log.warn("⚠ Ошибка стратегии {}: {}", type, e.getMessage());
            }
        }

        String decision = combineSignals(signals);

        switch (decision.toUpperCase(Locale.ROOT)) {
            case "BUY" ->
                    placeOrder(chatId, "BTCUSDT", "BUY", BigDecimal.valueOf(0.001));
            case "SELL" ->
                    placeOrder(chatId, "BTCUSDT", "SELL", BigDecimal.valueOf(0.001));
            default -> log.info("HOLD — пропуск");
        }
    }

    private String combineSignals(List<String> signals) {
        long buy = signals.stream().filter(s -> s.equalsIgnoreCase("BUY")).count();
        long sell = signals.stream().filter(s -> s.equalsIgnoreCase("SELL")).count();

        if (buy > sell) return "BUY";
        if (sell > buy) return "SELL";
        return "HOLD";
    }

    // ===================== Создание ордеров =====================

    private void placeOrder(Long chatId, String symbol, String side, BigDecimal qty) {
        try {
            Order order = orderService.placeMarket(
                    chatId,
                    symbol,
                    side,
                    qty,
                    BigDecimal.ZERO,
                    "AI_ORCHESTRATOR"
            );

            log.info("✅ Оркестратор создал ордер: {}", order);

        } catch (Exception e) {
            log.error("❌ Ошибка при создании ордера: {}", e.getMessage());
        }
    }

    // ===================== Панель =====================

    @Data
    @AllArgsConstructor
    public static class StrategyStatus {
        private StrategyType type;
        private String state;
        private String symbol;
    }

    public List<StrategyStatus> getStatuses(long chatId) {
        List<StrategyStatus> list = new ArrayList<>();
        Set<StrategyType> active = activeStrategies.getOrDefault(chatId, Collections.emptySet());

        strategyRegistry.getAll().keySet().forEach(type ->
                list.add(new StrategyStatus(type,
                        active.contains(type) ? "ACTIVE" : "INACTIVE",
                        "BTCUSDT"))
        );

        return list;
    }
}
