package com.chicu.aitradebot.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.journal.TradeIntentEvent;

import java.math.BigDecimal;

public interface TradeJournalGateway {

    String recordIntent(
            Long chatId,
            StrategyType strategyType,
            String exchangeName,
            NetworkType networkType,
            String symbol,
            String timeframe,
            TradeIntentEvent.Signal signal,
            TradeIntentEvent.Decision decision,
            String reasonCode,
            BigDecimal confidence,
            BigDecimal expectedReturn,
            BigDecimal uncertainty,
            String modelVersion,
            String effectiveSettingsJson,
            String featuresJson
    );

    void attachClientOrderId(String correlationId, String clientOrderId);

    void linkClientOrder(
            Long chatId,
            StrategyType strategyType,
            String exchangeName,
            NetworkType networkType,
            String symbol,
            String timeframe,
            String correlationId,
            String clientOrderId,
            String role
    );
}
