package com.chicu.aitradebot.exchange.config;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.exchange.binance.BinanceExchangeClient;
import com.chicu.aitradebot.exchange.bybit.BybitExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Регистрирует всех биржевых клиентов в единой фабрике ExchangeClientFactory
 * согласно архитектуре v4.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ExchangeClientConfig {

    private final ExchangeClientFactory factory;

    private final BinanceExchangeClient binanceClient;
    private final BybitExchangeClient bybitClient;

    @PostConstruct
    public void register() {
        log.info("🔧 Регистрация клиентов бирж…");

        // BINANCE
        factory.register("BINANCE", NetworkType.MAINNET, binanceClient);
        factory.register("BINANCE", NetworkType.TESTNET, binanceClient);

        // BYBIT
        factory.register("BYBIT", NetworkType.MAINNET, bybitClient);
        factory.register("BYBIT", NetworkType.TESTNET, bybitClient);

        log.info("✅ Клиенты бирж успешно зарегистрированы");
    }
}
