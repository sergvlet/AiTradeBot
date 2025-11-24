package com.chicu.aitradebot.orchestrator;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AiStrategyOrchestrator (v4)
 *
 * Чистая централизованная точка управления стратегиями + ордерами.
 * НЕ создаёт экземпляры стратегий.
 * НЕ знает про Binance/Bybit.
 * НЕ выполняет циклы стратегий.
 *
 * Все методы совместимы с твоим OrderService и Web-фасадами.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiStrategyOrchestrator {

    private final OrderService orderService;

    /** Активные стратегии: chatId → StrategyType */
    private final Map<Long, Set<StrategyType>> activeStrategies = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("🧠 AiStrategyOrchestrator v4 initialized.");
    }

    // =====================================================================
    // ▶️ START / STOP STRATEGY
    // =====================================================================

    public void startStrategy(Long chatId, StrategyType type) {
        activeStrategies
                .computeIfAbsent(chatId, k -> new HashSet<>())
                .add(type);

        log.info("▶️ Strategy {} STARTED (chatId={})", type, chatId);
    }

    public void stopStrategy(Long chatId, StrategyType type) {
        Optional.ofNullable(activeStrategies.get(chatId))
                .ifPresent(set -> set.remove(type));

        log.info("⏹ Strategy {} STOPPED (chatId={})", type, chatId);
    }

    public boolean isActive(Long chatId, StrategyType type) {
        return activeStrategies
                .getOrDefault(chatId, Collections.emptySet())
                .contains(type);
    }

    // =====================================================================
    // 📋 LIST OF STRATEGIES FOR UI
    // =====================================================================

    public record StrategyInfo(
            StrategyType type,
            boolean active
    ) {}

    public List<StrategyInfo> getStrategies(Long chatId) {
        Set<StrategyType> act = activeStrategies.getOrDefault(chatId, Set.of());
        List<StrategyInfo> list = new ArrayList<>();

        for (StrategyType t : StrategyType.values()) {
            list.add(new StrategyInfo(t, act.contains(t)));
        }
        return list;
    }

    // =====================================================================
    // 💰 ORDER MANAGEMENT (FULLY COMPATIBLE WITH YOUR OrderService)
    // =====================================================================

    public record OrderResult(
            boolean success,
            String message,
            Long orderId
    ) {}

    public record OrderView(
            Long id,
            String symbol,
            String side,
            String status,
            BigDecimal price,
            BigDecimal quantity,
            Boolean filled,
            Long timestamp
    ) {}

    // ---- BUY -------------------------------------------------------------

    public OrderResult marketBuy(Long chatId, String symbol, BigDecimal qty) {
        try {
            Order order = orderService.placeMarket(
                    chatId,
                    symbol,
                    "BUY",
                    qty,
                    BigDecimal.ZERO,      // executionPrice
                    "ORCHESTRATOR"        // strategyType
            );

            return new OrderResult(
                    true,
                    "BUY OK",
                    order.getId()         // dto → id is correct
            );
        } catch (Exception e) {
            log.error("❌ Error marketBuy: {}", e.getMessage());
            return new OrderResult(false, e.getMessage(), null);
        }
    }

    // ---- SELL ------------------------------------------------------------

    public OrderResult marketSell(Long chatId, String symbol, BigDecimal qty) {
        try {
            Order order = orderService.placeMarket(
                    chatId,
                    symbol,
                    "SELL",
                    qty,
                    BigDecimal.ZERO,
                    "ORCHESTRATOR"
            );

            return new OrderResult(
                    true,
                    "SELL OK",
                    order.getId()
            );
        } catch (Exception e) {
            log.error("❌ Error marketSell: {}", e.getMessage());
            return new OrderResult(false, e.getMessage(), null);
        }
    }

    // ---- CANCEL ----------------------------------------------------------

    public boolean cancelOrder(Long chatId, long orderId) {
        try {
            return orderService.cancelOrder(chatId, orderId);
        } catch (Exception e) {
            log.error("❌ Error cancelOrder: {}", e.getMessage());
            return false;
        }
    }

    // ---- LIST ORDERS (DTO) -----------------------------------------------

    public List<OrderView> listOrders(Long chatId, String symbol) {
        try {
            List<Order> orders = orderService.getOrdersByChatIdAndSymbol(chatId, symbol);

            return orders.stream()
                    .map(o -> new OrderView(
                            o.getId(),
                            o.getSymbol(),
                            o.getSide(),
                            o.getStatus(),
                            o.getPrice(),
                            o.getQuantity(),
                            o.isFilled(),
                            o.getTimestamp()
                    ))
                    .toList();

        } catch (Exception e) {
            log.error("❌ Error listOrders: {}", e.getMessage());
            return List.of();
        }
    }

    // =====================================================================
    // 💵 BALANCE (ПОКА ЗАГЛУШКА)
    // =====================================================================

    public record BalanceView(
            BigDecimal total,
            BigDecimal free,
            BigDecimal locked
    ) {}

    public record AssetBalanceView(
            String asset,
            BigDecimal free,
            BigDecimal locked
    ) {}

    public BalanceView getBalance(Long chatId) {
        // позже подключим ExchangeClient
        return new BalanceView(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public List<AssetBalanceView> getAssets(Long chatId) {
        return List.of(); // пока пусто
    }

    // =====================================================================
    // 📊 GLOBAL STATE for Dashboard
    // =====================================================================

    public record GlobalState(
            BigDecimal totalBalance,
            BigDecimal totalProfitPct,
            int activeStrategies
    ) {}

    public GlobalState getGlobalState(Long chatId) {
        int active = activeStrategies.getOrDefault(chatId, Set.of()).size();
        return new GlobalState(BigDecimal.ZERO, BigDecimal.ZERO, active);
    }
}
