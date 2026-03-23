package com.chicu.aitradebot.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.exchange.model.Order;

import java.math.BigDecimal;
import java.util.List;

public interface OrderService {

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

    Order placeMarket(OrderContext ctx,
                      OrderSide side,
                      BigDecimal amount,
                      BigDecimal executionPrice);

    Order placeLimit(OrderContext ctx,
                     OrderSide side,
                     BigDecimal quantity,
                     BigDecimal limitPrice,
                     String timeInForce);

    Order placeOco(OrderContext ctx,
                   BigDecimal quantity,
                   BigDecimal takeProfitPrice,
                   BigDecimal stopPrice,
                   BigDecimal stopLimitPrice);

    default Order placeMarketBuyQuote(OrderContext ctx, BigDecimal quoteAmount, BigDecimal executionPrice) {
        return placeMarket(ctx, OrderSide.BUY, quoteAmount, executionPrice);
    }

    default Order placeMarketSellQty(OrderContext ctx, BigDecimal baseQty, BigDecimal executionPrice) {
        return placeMarket(ctx, OrderSide.SELL, baseQty, executionPrice);
    }

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

    boolean cancelOrder(Long chatId, Long orderId);

    int cancelAllOpen(Long chatId, String symbol);

    List<Order> getOpenOrders(Long chatId, String symbol);

    default Order createOrder(Order order) {
        return createOrder(order, null, null);
    }

    Order createOrder(Order order, String exchangeName, NetworkType networkType);

    List<Order> getOrdersByChatIdAndSymbol(long chatId, String symbol);

    List<OrderEntity> getOrderEntitiesByChatIdAndSymbol(long chatId, String symbol);

    BigDecimal getStepSize(String exchangeName, NetworkType networkType, String symbol);

    BigDecimal getMinNotional(String exchangeName, NetworkType networkType, String symbol);
}
