package com.chicu.aitradebot.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.exchange.model.Order;

import java.math.BigDecimal;
import java.util.List;

public interface OrderService {

    // =========================
    // ✅ Контекст ордера (для журнала/обучения)
    // =========================
    record OrderContext(
            Long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            String correlationId,
            String role,
            String exchangeName,
            NetworkType networkType
    ) {}

    // =====================================================
    // ✅ НОВОЕ API (строгое, без путаницы)
    // =====================================================

    /**
     * MARKET ордер.

     * Семантика amount:
     * - BUY (SPOT): amount = quoteAmount (например, USDT budget), который готов потратить
     * - SELL (SPOT): amount = baseQty (количество базовой монеты), которое продаёшь
     */
    Order placeMarket(OrderContext ctx,
                      OrderSide side,
                      BigDecimal amount,
                      BigDecimal executionPrice);

    /**
     * LIMIT ордер (quantity = baseQty).
     */
    Order placeLimit(OrderContext ctx,
                     OrderSide side,
                     BigDecimal quantity,
                     BigDecimal limitPrice,
                     String timeInForce);

    /**
     * OCO (quantity = baseQty). Side по умолчанию SELL.
     */
    Order placeOco(OrderContext ctx,
                   BigDecimal quantity,
                   BigDecimal takeProfitPrice,
                   BigDecimal stopPrice,
                   BigDecimal stopLimitPrice);

    // =====================================================
    // ✅ Convenience
    // =====================================================

    default Order placeMarketBuyQuote(OrderContext ctx, BigDecimal quoteAmount, BigDecimal executionPrice) {
        return placeMarket(ctx, OrderSide.BUY, quoteAmount, executionPrice);
    }

    default Order placeMarketSellQty(OrderContext ctx, BigDecimal baseQty, BigDecimal executionPrice) {
        return placeMarket(ctx, OrderSide.SELL, baseQty, executionPrice);
    }

    // =====================================================
    // ⚠️ Старые методы (оставляем для совместимости)
    // =====================================================

    Order placeMarket(Long chatId,
                      String symbol,
                      String side,
                      BigDecimal amount,
                      BigDecimal executionPrice,
                      String strategyType);

    Order placeLimit(Long chatId,
                     String symbol,
                     String side,
                     BigDecimal quantity,
                     BigDecimal limitPrice,
                     String timeInForce,
                     String strategyType);

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

    // ====== OPEN ORDERS (пока DB; синхронизацию с биржей добавим следующим шагом) ======
    List<Order> getOpenOrders(Long chatId, String symbol);

    // ====== CREATE (generic) ======
    Order createOrder(Order order);

    // ====== ИСТОРИЯ ДЛЯ СТРАТЕГИЙ (DTO) ======
    List<Order> getOrdersByChatIdAndSymbol(long chatId, String symbol);

    // ====== ИСТОРИЯ ДЛЯ ДАШБОРДА / ГРАФИКА (ENTITY) ======
    List<OrderEntity> getOrderEntitiesByChatIdAndSymbol(long chatId, String symbol);
}
