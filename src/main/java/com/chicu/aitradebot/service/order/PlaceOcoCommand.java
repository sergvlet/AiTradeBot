package com.chicu.aitradebot.service.order;

import com.chicu.aitradebot.common.enums.StrategyType;

import java.math.BigDecimal;

public record PlaceOcoCommand(
        Long chatId,
        StrategyType strategyType,
        String symbol,

        BigDecimal quantity,

        BigDecimal takeProfitPrice,
        BigDecimal stopPrice,
        BigDecimal stopLimitPrice,

        String correlationId,
        String clientOrderId
) {}
