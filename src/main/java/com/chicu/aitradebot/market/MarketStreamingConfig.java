package com.chicu.aitradebot.market;

import com.chicu.aitradebot.market.ws.binance.BinancePublicTradeStreamService;
import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionCandleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class MarketStreamingConfig {

    private final BinancePublicTradeStreamService binanceStream;
    private final SmartFusionCandleService candleService;

    @PostConstruct
    public void init() {
        log.info("🌐 MarketStreamingConfig: инициализация потоков...");

        // Подключаем SmartFusionCandleService как listener (все трейды)
        binanceStream.setListener(candleService);

        log.info("✅ MarketStreamingConfig: готов.");
    }
}
