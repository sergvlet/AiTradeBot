package com.chicu.aitradebot.exchange.config;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.exchange.binance.BinanceExchangeClient;
import com.chicu.aitradebot.exchange.bybit.BybitExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Slf4j
@Configuration
public class ExchangeClientConfig {
    @Bean
    public ExchangeClientFactory exchangeClientFactory(ExchangeSettingsService settingsService) {
        ExchangeClientFactory factory = new ExchangeClientFactory();

        factory.register("BINANCE", NetworkType.MAINNET, new BinanceExchangeClient(false, settingsService));
        factory.register("BINANCE", NetworkType.TESTNET, new BinanceExchangeClient(true, settingsService));

        factory.register("BYBIT", NetworkType.MAINNET, new BybitExchangeClient(false, settingsService));
        factory.register("BYBIT", NetworkType.TESTNET, new BybitExchangeClient(true, settingsService));

        log.info("✅ ExchangeClientFactory инициализирована (4 клиентов)");
        return factory;
    }


}
