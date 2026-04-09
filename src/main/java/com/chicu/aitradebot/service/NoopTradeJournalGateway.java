package com.chicu.aitradebot.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.journal.TradeIntentEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fallback-заглушка для журнала.
 * Регистрируется только если реальный TradeJournalGateway отсутствует.
 */
@Service("serviceNoopTradeJournalGateway")
@ConditionalOnMissingBean(TradeJournalGateway.class)
public class NoopTradeJournalGateway implements TradeJournalGateway {

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
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public void attachClientOrderId(String correlationId, String clientOrderId) {
        // noop
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
        // noop
    }
}
