package com.chicu.aitradebot.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.journal.TradeIntentEvent;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Заглушка для журнала.
 * Нужна чтобы проект компилился/стартовал, даже если journaling ещё не внедрён полностью.
 *
 * ⚠️ Если у тебя уже есть реальный TradeJournalGatewayImpl — удали этот класс
 * или убери @Primary, иначе он будет перехватывать бин.
 */
@Service
@Primary
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

        // Возвращаем корреляцию, чтобы OrderService мог связать clientOrderId
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
