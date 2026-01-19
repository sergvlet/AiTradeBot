package com.chicu.aitradebot.journal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeOutcomeAssemblerService {

    private final TradeExecutionEventRepository execRepo;
    private final TradeOutcomeRepository outcomeRepo;

    /**
     * ✅ Этот метод нужен твоему TradeOutcomeReconcileJob (у тебя была ошибка компиляции).
     * Собирает/обновляет outcome по цепочке событий, привязанной к конкретному clientOrderId.
     */
    @Transactional
    public Optional<TradeOutcome> reconcileByClientOrderId(String clientOrderId) {
        if (clientOrderId == null || clientOrderId.isBlank()) return Optional.empty();

        String correlationId = OrderCorrelation.extractCorrelationId(clientOrderId);
        if (correlationId == null || correlationId.isBlank()) {
            // fallback: если clientOrderId не по нашему формату
            correlationId = clientOrderId.trim();
        }

        // Если уже есть outcome — вернём его (можно позже делать update)
        Optional<TradeOutcome> existing = outcomeRepo.findByCorrelationId(correlationId);
        if (existing.isPresent()) return existing;

        List<TradeExecutionEvent> events = execRepo.findByClientOrderIdOrderByEventTimeAsc(clientOrderId);
        if (events == null || events.isEmpty()) return Optional.empty();

        TradeExecutionEvent first = events.getFirst();

        // 1) находим entry fill: первый FILLED с trade id (или qty>0)
        TradeExecutionEvent entry = events.stream()
                .filter(e -> isFilled(e) && hasTradeId(e))
                .findFirst()
                .orElse(null);

        // 2) находим exit fill: последний FILLED (может быть тот же, если ещё не закрыто)
        TradeExecutionEvent exit = events.stream()
                .filter(e -> isFilled(e) && hasTradeId(e))
                .max(Comparator.comparing(e -> safeTime(e.getEventTime())))
                .orElse(null);

        boolean closed = entry != null && exit != null && exit != entry;

        BigDecimal feesAmount = sumFees(events);
        String feesAsset = detectFeesAsset(events);

        BigDecimal pnlPct = null;
        if (closed && entry.getPrice() != null && exit.getPrice() != null && entry.getPrice().signum() > 0) {
            pnlPct = calcPnlPct(entry.getSide(), entry.getPrice(), exit.getPrice());
        }

        TradeOutcome out = TradeOutcome.builder()
                .correlationId(correlationId)

                .chatId(first.getChatId())
                .strategyType(first.getStrategyType())
                .exchangeName(first.getExchangeName())

                // ✅ теперь outcome.networkType = enum, поэтому ошибки "NetworkType -> String" не будет
                .networkType(first.getNetworkType())

                .symbol(first.getSymbol())
                .timeframe(first.getTimeframe())

                .entrySide(entry != null ? entry.getSide() : first.getSide())
                .entryPrice(entry != null ? entry.getPrice() : null)
                .entryQty(entry != null ? entry.getQty() : null)

                .exitPrice(closed ? exit.getPrice() : null)
                .exitQty(closed ? exit.getQty() : null)

                .pnlPct(pnlPct)
                .feesAmount(feesAmount)
                .feesAsset(feesAsset)

                .status(closed ? "CLOSED" : "OPEN")
                .outcomeType(closed ? "UNKNOWN" : "OPEN")

                .entryClientOrderId(entry != null ? entry.getClientOrderId() : first.getClientOrderId())
                .exitClientOrderId(closed ? exit.getClientOrderId() : null)

                .entryExchangeOrderId(entry != null ? entry.getExchangeOrderId() : first.getExchangeOrderId())
                .exitExchangeOrderId(closed ? exit.getExchangeOrderId() : null)

                // ✅ FIX: раньше ты звал getTradeId(), теперь это exchangeTradeId
                .entryExchangeTradeId(entry != null ? entry.getExchangeTradeId() : first.getExchangeTradeId())
                .exitExchangeTradeId(closed ? exit.getExchangeTradeId() : null)

                .openedAt(entry != null ? entry.getEventTime() : first.getEventTime())
                .closedAt(closed ? exit.getEventTime() : null)
                .build();

        TradeOutcome saved = outcomeRepo.save(out);

        log.debug("🧩 OUTCOME saved: corr={}, status={}, symbol={}",
                saved.getCorrelationId(), saved.getStatus(), saved.getSymbol());

        return Optional.of(saved);
    }

    private static boolean isFilled(TradeExecutionEvent e) {
        if (e == null) return false;
        String st = e.getStatus();
        return st != null && st.equalsIgnoreCase("FILLED");
    }

    private static boolean hasTradeId(TradeExecutionEvent e) {
        return e != null && e.getExchangeTradeId() != null && !e.getExchangeTradeId().isBlank();
    }

    private static Instant safeTime(Instant t) {
        return t != null ? t : Instant.EPOCH;
    }

    private static BigDecimal sumFees(List<TradeExecutionEvent> events) {
        BigDecimal sum = BigDecimal.ZERO;
        for (TradeExecutionEvent e : events) {
            if (e.getFeeAmount() != null) sum = sum.add(e.getFeeAmount());
        }
        return sum.signum() == 0 ? null : sum;
    }

    private static String detectFeesAsset(List<TradeExecutionEvent> events) {
        return events.stream()
                .map(TradeExecutionEvent::getFeeAsset)
                .filter(a -> a != null && !a.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static BigDecimal calcPnlPct(String entrySide, BigDecimal entryPrice, BigDecimal exitPrice) {
        // BUY: (exit-entry)/entry
        // SELL: (entry-exit)/entry
        BigDecimal diff = exitPrice.subtract(entryPrice);
        if (entrySide != null && entrySide.equalsIgnoreCase("SELL")) {
            diff = entryPrice.subtract(exitPrice);
        }
        return diff
                .divide(entryPrice, 12, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(6, RoundingMode.HALF_UP);
    }
}
