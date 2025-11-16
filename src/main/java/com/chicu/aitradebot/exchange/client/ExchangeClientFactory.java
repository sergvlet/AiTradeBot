package com.chicu.aitradebot.exchange.client;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 🏗️ Фабрика клиентов бирж
 * Регистрация клиентов: BINANCE_MAINNET, BINANCE_TESTNET, BYBIT_MAINNET, BYBIT_TESTNET
 * Позволяет безопасно получать ExchangeClient по настройкам пользователя.
 */
@Slf4j
@Component
public class ExchangeClientFactory {

    /** Все клиенты регистрируются в виде ключей: EXCHANGE_NETWORK (например: BINANCE_MAINNET) */
    private final Map<String, ExchangeClient> registry = new ConcurrentHashMap<>();

    /**
     * Регистрация клиента.
     *
     * @param exchange     название биржи (например, "BINANCE")
     * @param networkType  тип сети (MAINNET или TESTNET)
     * @param client       экземпляр клиента
     */
    public void register(String exchange, NetworkType networkType, ExchangeClient client) {
        String key = buildKey(exchange, networkType);

        if (registry.containsKey(key)) {
            log.warn("⚠️ Клиент {} уже зарегистрирован — пропускаем дубликат.", key);
            return;
        }

        registry.put(key, client);
        log.info("🔹 Зарегистрирован клиент {}", key);
    }

    /**
     * Получить клиента по настройкам пользователя.
     */
    public ExchangeClient getClient(ExchangeSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("ExchangeSettings не может быть null");
        }
        if (settings.getExchange() == null || settings.getNetwork() == null) {
            throw new IllegalArgumentException("ExchangeSettings не содержит exchange или network!");
        }

        String key = buildKey(settings.getExchange(), settings.getNetwork());
        ExchangeClient client = registry.get(key);

        if (client == null) {
            throw new IllegalArgumentException("❌ Клиент для " + key + " не найден!");
        }

        log.info("🔗 Выбран клиент {}", key);
        return client;
    }

    /**
     * Получить клиента напрямую по названию биржи и типу сети.
     */
    public ExchangeClient getClient(String exchange, NetworkType networkType) {
        String key = buildKey(exchange, networkType);
        ExchangeClient client = registry.get(key);

        if (client == null) {
            throw new IllegalArgumentException("❌ Клиент для " + key + " не найден!");
        }

        log.info("🔗 Выбран клиент {}", key);
        return client;
    }

    /**
     * Формирует ключ BINANCE_MAINNET / BYBIT_TESTNET.
     */
    private String buildKey(String exchange, NetworkType networkType) {
        return exchange.toUpperCase() + "_" + networkType.name();
    }

    /**
     * Проверка, зарегистрирован ли клиент.
     */
    public boolean hasClient(String exchange, NetworkType networkType) {
        return registry.containsKey(buildKey(exchange, networkType));
    }

    /**
     * Отладочный вывод зарегистрированных клиентов.
     */
    public void printRegistry() {
        log.info("📋 Зарегистрированные клиенты: {}", registry.keySet());
    }

}
