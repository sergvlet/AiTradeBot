package com.chicu.aitradebot.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.OrderEntity;
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
    // ✅ НОВОЕ API (с контекстом)
    // =====================================================

    /**
     * MARKET ордер.
     *
     * ВАЖНО: семантика параметра {@code amount} зависит от {@code side}:
     * - BUY (SPOT): {@code amount} = quoteAmount (например, USDT), который ты готов потратить.
     *              Сервис обязан сам рассчитать baseQty с учётом stepSize/minNotional/precision и НЕ превысить budget.
     * - SELL (SPOT): {@code amount} = baseQty (количество базовой монеты), которое ты продаёшь.
     *
     * {@code executionPrice} может быть последним тиком/оценкой для guard-проверок (minNotional и т.п.).
     */
    Order placeMarket(OrderContext ctx,
                      String side,
                      BigDecimal amount,
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

    // =====================================================
    // ✅ ГОВОРЯЩИЕ convenience-методы (чтобы не путаться)
    // =====================================================

    /**
     * SPOT BUY на MARKET: ты передаёшь budget в quote-валюте (обычно USDT).
     */
    default Order placeMarketBuyQuote(OrderContext ctx,
                                      BigDecimal quoteAmount,
                                      BigDecimal executionPrice) {
        return placeMarket(ctx, "BUY", quoteAmount, executionPrice);
    }

    /**
     * SPOT SELL на MARKET: ты передаёшь qty базовой монеты.
     */
    default Order placeMarketSellQty(OrderContext ctx,
                                     BigDecimal baseQty,
                                     BigDecimal executionPrice) {
        return placeMarket(ctx, "SELL", baseQty, executionPrice);
    }

    // =====================================================
    // ⚠️ Старые методы (оставляем для совместимости)
    // =====================================================

    /**
     * Старый MARKET API.
     *
     * Семантика {@code amount} такая же:
     * - BUY: quoteAmount (USDT budget)
     * - SELL: baseQty
     */
    Order placeMarket(Long chatId,
                      String symbol,
                      String side,
                      BigDecimal amount,
                      BigDecimal executionPrice,
                      String strategyType);

    /**
     * Старый LIMIT API (оставляем как было: quantity = baseQty).
     */
    Order placeLimit(Long chatId,
                     String symbol,
                     String side,
                     BigDecimal quantity,
                     BigDecimal limitPrice,
                     String timeInForce,
                     String strategyType);

    /**
     * Старый OCO API (quantity = baseQty).
     */
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
