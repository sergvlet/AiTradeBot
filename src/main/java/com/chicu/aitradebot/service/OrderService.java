package com.chicu.aitradebot.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.exchange.model.Order;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

public interface OrderService {

    record OrderContext(
            Long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            String correlationId,
            String role,
            String exchangeName,
            NetworkType networkType,
            String intent,
            String positionUid
    ) {
        public OrderContext(
                Long chatId,
                StrategyType strategyType,
                String symbol,
                String timeframe,
                String correlationId,
                String role,
                String exchangeName,
                NetworkType networkType
        ) {
            this(
                    chatId,
                    strategyType,
                    symbol,
                    timeframe,
                    correlationId,
                    role,
                    exchangeName,
                    networkType,
                    null,
                    null
            );
        }

        public OrderContext(
                Long chatId,
                StrategyType strategyType,
                String symbol,
                String timeframe,
                String correlationId,
                String role,
                String exchangeName,
                NetworkType networkType,
                String intent
        ) {
            this(
                    chatId,
                    strategyType,
                    symbol,
                    timeframe,
                    correlationId,
                    role,
                    exchangeName,
                    networkType,
                    intent,
                    null
            );
        }

        public static OrderContext entry(
                Long chatId,
                StrategyType strategyType,
                String symbol,
                String timeframe,
                String correlationId,
                String exchangeName,
                NetworkType networkType,
                String positionUid
        ) {
            return new OrderContext(
                    chatId,
                    strategyType,
                    symbol,
                    timeframe,
                    correlationId,
                    "ENTRY",
                    exchangeName,
                    networkType,
                    "ENTRY",
                    positionUid
            );
        }

        public static OrderContext exit(
                Long chatId,
                StrategyType strategyType,
                String symbol,
                String timeframe,
                String correlationId,
                String exchangeName,
                NetworkType networkType,
                String positionUid
        ) {
            return new OrderContext(
                    chatId,
                    strategyType,
                    symbol,
                    timeframe,
                    correlationId,
                    "EXIT",
                    exchangeName,
                    networkType,
                    "EXIT",
                    positionUid
            );
        }

        public static OrderContext oco(
                Long chatId,
                StrategyType strategyType,
                String symbol,
                String timeframe,
                String correlationId,
                String exchangeName,
                NetworkType networkType,
                String positionUid
        ) {
            return new OrderContext(
                    chatId,
                    strategyType,
                    symbol,
                    timeframe,
                    correlationId,
                    "OCO",
                    exchangeName,
                    networkType,
                    "OCO",
                    positionUid
            );
        }

        public String safeIntent() {
            if (intent == null || intent.isBlank()) {
                if (role == null || role.isBlank()) {
                    return null;
                }
                return role.trim().toUpperCase(Locale.ROOT);
            }
            return intent.trim().toUpperCase(Locale.ROOT);
        }

        public String safeRole() {
            if (role == null || role.isBlank()) {
                if (intent == null || intent.isBlank()) {
                    return null;
                }
                return intent.trim().toUpperCase(Locale.ROOT);
            }
            return role.trim().toUpperCase(Locale.ROOT);
        }
    }

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

    List<Order> getOpenOrders(Long chatId,
                              String exchangeName,
                              NetworkType networkType,
                              String symbol);

    default Order createOrder(Order order) {
        return createOrder(order, null, null);
    }

    Order createOrder(Order order, String exchangeName, NetworkType networkType);

    List<Order> getOrdersByChatIdAndSymbol(long chatId, String symbol);

    List<OrderEntity> getOrderEntitiesByChatIdAndSymbol(long chatId, String symbol);

    BigDecimal getStepSize(String exchangeName, NetworkType networkType, String symbol);

    BigDecimal getMinNotional(String exchangeName, NetworkType networkType, String symbol);
}

