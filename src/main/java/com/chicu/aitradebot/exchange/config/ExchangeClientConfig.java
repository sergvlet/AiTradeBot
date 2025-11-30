package com.chicu.aitradebot.exchange.config;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.exchange.binance.BinanceExchangeClient;
import com.chicu.aitradebot.exchange.bybit.BybitExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ExchangeClientConfig {

    private final ExchangeClientFactory factory;
    private final ExchangeSettingsService settingsService;

    @PostConstruct
    public void init() {

        BinanceExchangeClient binance = new BinanceExchangeClient(settingsService);

        // Регистрация одна и та же (сам клиент), отличие в URL будет через settings
        factory.register("BINANCE", NetworkType.MAINNET, binance);
        factory.register("BINANCE", NetworkType.TESTNET, binance);

        log.info("✔ BinanceExchangeClient registered for MAINNET & TESTNET");
    }
}

