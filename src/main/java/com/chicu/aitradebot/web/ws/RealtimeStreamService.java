package com.chicu.aitradebot.web.ws;

import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.web.ws.dto.RealtimeCandleDto;
import com.chicu.aitradebot.web.ws.dto.RealtimeTradeDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class RealtimeStreamService {

    private final CandleWebSocketHandler candleWebSocketHandler;
    private final TradeWebSocketHandler tradeWebSocketHandler;

    // Свеча с биржи/сервиса
    public void sendCandle(String symbol,
                           Instant ts,
                           BigDecimal open,
                           BigDecimal high,
                           BigDecimal low,
                           BigDecimal close,
                           BigDecimal volume) {

        RealtimeCandleDto dto = RealtimeCandleDto.builder()
                .time(ts.toEpochMilli())
                .open(toD(open))
                .high(toD(high))
                .low(toD(low))
                .close(toD(close))
                .volume(toD(volume))
                .build();

        candleWebSocketHandler.broadcastCandle(symbol, dto);
    }

    // Сделка из локальной БД
    public void sendTrade(OrderEntity e) {
        if (e.getChatId() == null || e.getSymbol() == null) return;

        RealtimeTradeDto dto = RealtimeTradeDto.builder()
                .id(e.getId())
                .chatId(e.getChatId())
                .symbol(e.getSymbol())
                .side(e.getSide())
                .price(toD(e.getPrice()))
                .qty(toD(e.getQuantity()))
                .status(e.getStatus())
                .strategyType(e.getStrategyType())
                .time(e.getTimestamp())
                .exitPrice(toD(e.getExitPrice()))
                .exitTime(e.getExitTimestamp())
                .tpPrice(toD(e.getTakeProfitPrice()))
                .slPrice(toD(e.getStopLossPrice()))
                .tpHit(e.getTpHit())
                .slHit(e.getSlHit())
                .pnlUsd(toD(e.getRealizedPnlUsd()))
                .pnlPct(e.getRealizedPnlPct() != null ? e.getRealizedPnlPct().doubleValue() : null)
                .entryReason(e.getEntryReason())
                .exitReason(e.getExitReason())
                .mlConfidence(e.getMlConfidence() != null ? e.getMlConfidence().doubleValue() : null)
                .build();

        tradeWebSocketHandler.broadcastTrade(e.getChatId(), e.getSymbol(), dto);
    }

    private double toD(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }
}
