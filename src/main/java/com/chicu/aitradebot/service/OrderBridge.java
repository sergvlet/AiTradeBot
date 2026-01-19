package com.chicu.aitradebot.service;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.service.order.PlaceOcoCommand;
import com.chicu.aitradebot.service.order.PlaceOrderCommand;

import java.math.BigDecimal;

final class OrderBridge {

    private OrderBridge() {}

    static PlaceOrderCommand market(Long chatId, String symbol, String side, BigDecimal qty,
                                    String strategyType, String correlationId, String clientOrderId) {

        return new PlaceOrderCommand(
                chatId,
                parseStrategy(strategyType),
                symbol,
                parseSide(side),
                qty,
                null,
                null,
                correlationId,
                clientOrderId
        );
    }

    static PlaceOrderCommand limit(Long chatId, String symbol, String side, BigDecimal qty,
                                   BigDecimal limitPrice, String tif, String strategyType,
                                   String correlationId, String clientOrderId) {

        return new PlaceOrderCommand(
                chatId,
                parseStrategy(strategyType),
                symbol,
                parseSide(side),
                qty,
                limitPrice,
                tif,
                correlationId,
                clientOrderId
        );
    }

    static PlaceOcoCommand oco(Long chatId, String symbol, BigDecimal qty,
                              BigDecimal tp, BigDecimal stop, BigDecimal stopLimit,
                              String strategyType, String correlationId, String clientOrderId) {

        return new PlaceOcoCommand(
                chatId,
                parseStrategy(strategyType),
                symbol,
                qty,
                tp, stop, stopLimit,
                correlationId,
                clientOrderId
        );
    }

    private static OrderSide parseSide(String s) {
        if (s == null) return OrderSide.BUY;
        return OrderSide.valueOf(s.trim().toUpperCase());
    }

    private static StrategyType parseStrategy(String s) {
        if (s == null) return StrategyType.SCALPING;
        return StrategyType.valueOf(s.trim().toUpperCase());
    }
}
