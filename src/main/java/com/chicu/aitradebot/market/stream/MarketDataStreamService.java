package com.chicu.aitradebot.market.stream;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.binance.ws.BinanceSpotWebSocketClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataStreamService {

    /**
     * Binance WS-клиент подтягивается из Spring-контекста,
     * у него уже внедрены BinanceKlineParser + MarketStreamService.
     */
    private final BinanceSpotWebSocketClient binanceSpotWebSocketClient;

    /**
     * Подписывает стратегию на Binance KLINES (UnifiedKline → MarketStreamService → StrategyLive)
     */
    public void subscribeCandles(long chatId,
                                 StrategyType strategyType,
                                 String symbol,
                                 String timeframe) {

        binanceSpotWebSocketClient.subscribeKline(
                symbol.toLowerCase(),
                timeframe,
                chatId,
                strategyType
        );

        log.info("📡 SUBSCRIBE Binance KLINE: {} {} (chatId={}, strategy={})",
                symbol, timeframe, chatId, strategyType);
    }
}
