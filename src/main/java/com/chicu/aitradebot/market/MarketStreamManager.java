package com.chicu.aitradebot.market;

import com.chicu.aitradebot.exchange.binance.BinanceWebSocketClient;
import com.chicu.aitradebot.market.aggregation.CandleAggregator;
import com.chicu.aitradebot.market.ws.TradeFeedListener;
import com.chicu.aitradebot.market.ws.binance.BinancePublicTradeStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketStreamManager {

    private final BinanceWebSocketClient binanceWs;
    private final CandleAggregator aggregator;

    public void start(String symbol) {
        aggregator.init(symbol);
        binanceWs.connect(symbol, aggregator);
        log.info("🚀 MarketStreamManager: realtime stream запущен для {}", symbol);
    }
}


