package com.chicu.aitradebot.market.ws;

import com.chicu.aitradebot.strategy.core.CandleProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeStreamService {

    private final CandleWebSocketHandler candleHandler;
    private final TradeWebSocketHandler tradeHandler;

    public void sendCandle(String symbol, String timeframe, CandleProvider.Candle c) {
        try {
            candleHandler.broadcastTick(symbol, timeframe, c);
        } catch (Exception ex) {
            log.error("❌ sendCandle {} {}: {}", symbol, timeframe, ex.getMessage());
        }
    }
}
