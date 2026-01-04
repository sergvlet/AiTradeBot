package com.chicu.aitradebot.journal;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeExecutionIngest(
        Long chatId,
        StrategyType strategyType,
        String exchangeName,
        NetworkType networkType,
        String symbol,
        String timeframe,

        String eventUid,
        String clientOrderId,
        String exchangeOrderId,
        String exchangeTradeId,

        String eventType,
        String side,
        String status,

        BigDecimal price,
        BigDecimal qty,
        BigDecimal quoteQty,

        String feeAsset,
        BigDecimal feeAmount,
        Boolean maker,

        Instant eventTime,
        String rawJson
) {}
