package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.journal.TradeIntentEvent;
import com.chicu.aitradebot.journal.TradeIntentJournalService;
import com.chicu.aitradebot.journal.TradeOrderLinkService;
import com.chicu.aitradebot.service.TradeJournalGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class TradeJournalGatewayImpl implements TradeJournalGateway {

    private final TradeIntentJournalService intentService;
    private final TradeOrderLinkService linkService;

    @Override
    public String recordIntent(Long chatId,
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
                               String featuresJson) {

        return intentService.recordIntent(
                chatId,
                strategyType,
                exchangeName,
                networkType,
                symbol,
                timeframe,
                signal,
                decision,
                reasonCode,
                confidence,
                expectedReturn,
                uncertainty,
                modelVersion,
                effectiveSettingsJson,
                featuresJson
        );
    }

    @Override
    public void attachClientOrderId(String correlationId, String clientOrderId) {
        intentService.attachClientOrderId(correlationId, clientOrderId);
    }

    @Override
    public void linkClientOrder(Long chatId,
                                StrategyType strategyType,
                                String exchangeName,
                                NetworkType networkType,
                                String symbol,
                                String timeframe,
                                String correlationId,
                                String clientOrderId,
                                String role) {

        linkService.link(
                chatId,
                strategyType,
                exchangeName,
                networkType,
                symbol,
                timeframe,
                correlationId,
                clientOrderId,
                role
        );
    }
}
