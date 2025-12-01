package com.chicu.aitradebot.exchange.client;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeClientFactory {

    private final ExchangeSettingsService exchangeSettingsService;

    /**
     * ЕДИНЫЙ реестр клиентов:
     *   "BINANCE_MAINNET"  → client
     *   "BINANCE_TESTNET"  → client
     *   "BYBIT_MAINNET"    → client
     *   "BYBIT_TESTNET"    → client
     */
    private final Map<String, ExchangeClient> registry = new ConcurrentHashMap<>();

    // -------------------------------------------------------
    // R E G I S T E R
    // -------------------------------------------------------
    public void register(String exchange, NetworkType network, ExchangeClient client) {
        String key = buildKey(exchange, network);

        if (registry.containsKey(key)) {
            log.warn("⚠️ Клиент уже зарегистрирован: {}", key);
            return;
        }

        registry.put(key, client);
        log.info("✅ Зарегистрирован клиент: {} -> {}", key, client.getClass().getSimpleName());
    }

    // -------------------------------------------------------
    // G E T  — по ExchangeSettings
    // -------------------------------------------------------
    public ExchangeClient getClient(ExchangeSettings settings) {

        if (settings == null)
            throw new IllegalArgumentException("ExchangeSettings не может быть null");

        return get(settings.getExchange(), settings.getNetwork());
    }

    // -------------------------------------------------------
    // G E T  — по имени биржи и сети
    // -------------------------------------------------------
    public ExchangeClient get(String exchange, NetworkType network) {

        String key = buildKey(exchange, network);

        ExchangeClient client = registry.get(key);

        if (client == null)
            throw new IllegalArgumentException("❌ Клиент не зарегистрирован: " + key);

        return client;
    }

    // -------------------------------------------------------
    // G E T  — по chatId (выбор активного подключения)
    // -------------------------------------------------------
    public ExchangeClient getClientByChatId(Long chatId) {

        ExchangeSettings s = exchangeSettingsService.findAllByChatId(chatId)
                .stream()
                .filter(ExchangeSettings::isEnabled)
                .findFirst()
                .orElseGet(() ->
                        exchangeSettingsService.getOrCreate(
                                chatId,
                                "BINANCE",
                                NetworkType.MAINNET
                        )
                );

        return getClient(s);
    }

    // -------------------------------------------------------
    // KEY BUILDER
    // -------------------------------------------------------
    private String buildKey(String exchange, NetworkType network) {
        return exchange.toUpperCase() + "_" + network.name();
    }
}
