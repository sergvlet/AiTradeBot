package com.chicu.aitradebot.market;

import com.chicu.aitradebot.market.ws.binance.BinancePublicTradeStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class MarketStreamingConfig {

    private final BinancePublicTradeStreamService binance;
    private final MarketStreamManager streamManager;  // ✔ есть

    @PostConstruct
    public void init() {
        log.info("🔌 MarketStreamingConfig: привязываем MarketStreamManager");

        // ТЕПЕРЬ MarketStreamManager — это TradeFeedListener
        binance.setListener(streamManager);

        // Для логов
        streamManager.subscribeSymbol("BTCUSDT");
        streamManager.subscribeSymbol("ETHUSDT");

        // Стартуем поток
        binance.subscribeSymbols(java.util.List.of("BTCUSDT", "ETHUSDT"));
    }
}
