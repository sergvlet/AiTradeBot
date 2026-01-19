package com.chicu.aitradebot.journal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionJournalService {

    private final TradeExecutionEventRepository repo;

    /**
     * Dedup по eventUid.
     * Возвращает сохранённое (или уже существующее) событие.
     */
    @Transactional
    public TradeExecutionEvent ingest(TradeExecutionIngest in) {
        if (in == null) throw new IllegalArgumentException("TradeExecutionIngest is null");
        if (in.eventUid() == null || in.eventUid().isBlank())
            throw new IllegalArgumentException("eventUid is required for execution journal");

        Optional<TradeExecutionEvent> existing = repo.findByEventUid(in.eventUid());
        if (existing.isPresent()) return existing.get();

        String correlationId = OrderCorrelation.extractCorrelationId(in.clientOrderId());

        TradeExecutionEvent e = TradeExecutionEvent.builder()
                .chatId(in.chatId())
                .strategyType(in.strategyType())
                .exchangeName(in.exchangeName())
                .networkType(in.networkType())
                .symbol(in.symbol())
                .timeframe(in.timeframe())

                .eventUid(in.eventUid())
                .correlationId(correlationId)
                .clientOrderId(in.clientOrderId())
                .exchangeOrderId(in.exchangeOrderId())
                .exchangeTradeId(in.exchangeTradeId())

                .eventType(in.eventType())
                .side(in.side())
                .status(in.status())

                .price(in.price())
                .qty(in.qty())
                .quoteQty(in.quoteQty())

                .feeAsset(in.feeAsset())
                .feeAmount(in.feeAmount())
                .maker(in.maker())

                .eventTime(in.eventTime() != null ? in.eventTime() : Instant.now())
                .rawJson(in.rawJson())
                .build();

        TradeExecutionEvent saved = repo.save(e);

        // без спама
        log.debug("🧾 EXEC saved: eventUid={}, corr={}, clientOrderId={}, type={}, status={}",
                saved.getEventUid(), saved.getCorrelationId(), saved.getClientOrderId(),
                saved.getEventType(), saved.getStatus());

        return saved;
    }
}
