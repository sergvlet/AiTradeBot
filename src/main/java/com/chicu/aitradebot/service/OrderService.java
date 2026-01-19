package com.chicu.aitradebot.service;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.exchange.model.Order;

import java.math.BigDecimal;
import java.util.List;

public interface OrderService {

    // =========================
    // ✅ НОВОЕ: контекст ордера (для журнала/обучения)
    // =========================
    record OrderContext(
            Long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            String correlationId, // id intent-а (из TradeIntentJournalService)
            String role           // ENTRY / TP / SL / EXIT / OCO / UNKNOWN
    ) {}

    // ✅ НОВОЕ: методы с контекстом (их будет звать SCALPING)
    Order placeMarket(OrderContext ctx,
                      String side,
                      BigDecimal quantity,
                      BigDecimal executionPrice);

    Order placeLimit(OrderContext ctx,
                     String side,
                     BigDecimal quantity,
                     BigDecimal limitPrice,
                     String timeInForce);

    Order placeOco(OrderContext ctx,
                   BigDecimal quantity,
                   BigDecimal takeProfitPrice,
                   BigDecimal stopPrice,
                   BigDecimal stopLimitPrice);

    // =========================
    // ⚠️ Старые методы (оставляем для совместимости)
    // =========================

    // ====== MARKET ======
    Order placeMarket(Long chatId,
                      String symbol,
                      String side,
                      BigDecimal quantity,
                      BigDecimal executionPrice,
                      String strategyType);

    // ====== LIMIT ======
    Order placeLimit(Long chatId,
                     String symbol,
                     String side,
                     BigDecimal quantity,
                     BigDecimal limitPrice,
                     String timeInForce,
                     String strategyType);

    // ====== OCO ======
    Order placeOco(Long chatId,
                   String symbol,
                   BigDecimal quantity,
                   BigDecimal takeProfitPrice,
                   BigDecimal stopPrice,
                   BigDecimal stopLimitPrice,
                   String strategyType);

    // ====== CANCEL ======
    boolean cancelOrder(Long chatId, Long orderId);

    int cancelAllOpen(Long chatId, String symbol);

    // ====== OPEN ORDERS ======
    List<Order> getOpenOrders(Long chatId, String symbol);

    // ====== CREATE (generic) ======
    Order createOrder(Order order);

    // ====== ИСТОРИЯ ДЛЯ СТРАТЕГИЙ (DTO) ======
    List<Order> getOrdersByChatIdAndSymbol(long chatId, String symbol);

    // ====== ИСТОРИЯ ДЛЯ ДАШБОРДА / ГРАФИКА (ENTITY) ======
    List<OrderEntity> getOrderEntitiesByChatIdAndSymbol(long chatId, String symbol);
}
