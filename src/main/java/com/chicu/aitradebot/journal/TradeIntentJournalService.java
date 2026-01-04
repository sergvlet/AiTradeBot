package com.chicu.aitradebot.journal;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeIntentJournalService {

    private final TradeIntentEventRepository repo;

    /**
     * Создаёт intent до выставления ордера.
     * Возвращает correlationId — его надо пронести дальше и вложить в clientOrderId.
     */
    @Transactional
    public String recordIntent(
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
    ) {

        Objects.requireNonNull(chatId, "chatId");
        Objects.requireNonNull(strategyType, "strategyType");
        Objects.requireNonNull(exchangeName, "exchangeName");
        Objects.requireNonNull(networkType, "networkType");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(timeframe, "timeframe");
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(decision, "decision");

        String correlationId = UUID.randomUUID().toString();

        TradeIntentEvent e = TradeIntentEvent.builder()
                .chatId(chatId)
                .strategyType(strategyType)
                .exchangeName(exchangeName)
                .networkType(networkType)
                .symbol(symbol)
                .timeframe(timeframe)
                .correlationId(correlationId)
                .signal(signal)
                .decision(decision)
                .reasonCode(reasonCode)
                .confidence(confidence)
                .expectedReturn(expectedReturn)
                .uncertainty(uncertainty)
                .modelVersion(modelVersion)
                .effectiveSettingsJson(effectiveSettingsJson)
                .featuresJson(featuresJson)
                .createdAt(Instant.now())
                .build();

        repo.save(e);

        // лог минимальный, без спама
        if (decision != TradeIntentEvent.Decision.ALLOW) {
            log.info("🧾 Intent: {} {} {} {} tf={} decision={} reason={} cid={}",
                    strategyType, symbol, exchangeName, networkType, timeframe, decision, safe(reasonCode), correlationId);
        } else {
            log.debug("🧾 Intent: {} {} {} {} tf={} decision=ALLOW cid={}",
                    strategyType, symbol, exchangeName, networkType, timeframe, correlationId);
        }

        return correlationId;
    }

    /**
     * Проставляем clientOrderId после того, как OrderService реально сформировал/отправил ордер.
     * Можно вызывать и для случая, когда clientOrderId отличается от correlationId.
     */
    @Transactional
    public void attachClientOrderId(String correlationId, String clientOrderId) {
        if (correlationId == null || correlationId.isBlank()) return;
        repo.findByCorrelationId(correlationId).ifPresent(e -> {
            e.setClientOrderId(clientOrderId);
            repo.save(e);
        });
    }

    private static String safe(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }
}
