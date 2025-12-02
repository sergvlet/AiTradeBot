package com.chicu.aitradebot.exchange.config;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.exchange.binance.BinanceExchangeClient;
import com.chicu.aitradebot.exchange.bybit.BybitExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Регистрация всех клиентов (Binance, Bybit)
 * Фабрика становится универсальной, без хардкода Binance.
 */
@Configuration
@RequiredArgsConstructor
public class ExchangeClientConfig {

    private final ExchangeClientFactory clientFactory;
    private final BinanceExchangeClient binanceClient;
    private final BybitExchangeClient bybitClient;

    @PostConstruct
    public void registerClients() {

        // ===== Binance main/test =====
        clientFactory.register(
                "BINANCE",
                NetworkType.MAINNET,
                binanceClient
        );

        clientFactory.register(
                "BINANCE",
                NetworkType.TESTNET,
                binanceClient
        );

        // ===== Bybit main/test =====
        clientFactory.register(
                "BYBIT",
                NetworkType.MAINNET,
                bybitClient
        );

        clientFactory.register(
                "BYBIT",
                NetworkType.TESTNET,
                bybitClient
        );
    }
}
