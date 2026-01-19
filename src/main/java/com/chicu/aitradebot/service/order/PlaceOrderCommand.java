package com.chicu.aitradebot.service.order;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.enums.OrderSide;

import java.math.BigDecimal;

public record PlaceOrderCommand(
        Long chatId,
        StrategyType strategyType,
        String symbol,
        OrderSide side,

        BigDecimal quantity,        // base qty (BTC), не quote
        BigDecimal limitPrice,      // только для LIMIT (иначе null)
        String timeInForce,         // только для LIMIT (иначе null)

        String correlationId,       // intentId / correlation key
        String clientOrderId        // newClientOrderId / orderLinkId
) {}
