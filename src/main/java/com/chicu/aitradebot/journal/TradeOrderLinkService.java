package com.chicu.aitradebot.journal;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeOrderLinkService {

    private final TradeOrderLinkRepository repo;

    /**
     * Вызывать в момент, когда ты УЖЕ знаешь clientOrderId (перед отправкой на биржу или сразу после).
     */
    @Transactional
    public void link(
            Long chatId,
            StrategyType strategyType,
            String exchangeName,
            NetworkType networkType,
            String symbol,
            String timeframe,
            String correlationId,
            String clientOrderId,
            String role
    ) {
        Objects.requireNonNull(chatId, "chatId");
        Objects.requireNonNull(strategyType, "strategyType");
        Objects.requireNonNull(exchangeName, "exchangeName");
        Objects.requireNonNull(networkType, "networkType");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(timeframe, "timeframe");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(clientOrderId, "clientOrderId");

        if (role == null || role.isBlank()) role = "UNKNOWN";

        // idempotent по clientOrderId
        if (repo.findByClientOrderId(clientOrderId).isPresent()) return;

        TradeOrderLink link = TradeOrderLink.builder()
                .chatId(chatId)
                .strategyType(strategyType)
                .exchangeName(exchangeName)
                .networkType(networkType)
                .symbol(symbol)
                .timeframe(timeframe)
                .correlationId(correlationId)
                .clientOrderId(clientOrderId)
                .role(role)
                .createdAt(Instant.now())
                .build();

        repo.save(link);

        log.debug("🔗 Link: cid={} clientOrderId={} role={}", correlationId, clientOrderId, role);
    }
}
