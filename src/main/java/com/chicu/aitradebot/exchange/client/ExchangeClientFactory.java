package com.chicu.aitradebot.exchange.client;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class ExchangeClientFactory {

    /** Сервис настроек биржи (что выбрал пользователь: BINANCE/BYBIT, MAINNET/TESTNET и т.д.) */
    private final ExchangeSettingsService exchangeSettingsService;

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
     * Получить клиента по сущности ExchangeSettings.
     */
    public ExchangeClient getClient(ExchangeSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("ExchangeSettings не может быть null");
        }
        if (settings.getExchange() == null || settings.getNetwork() == null) {
            throw new IllegalArgumentException("ExchangeSettings не содержит exchange или network!");
        }

        return getClient(settings.getExchange(), settings.getNetwork());
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

        log.debug("🔗 Выбран клиент {}", key);
        return client;
    }

    /**
     * Получить клиента по chatId.
     *
     * ⚠️ ВРЕМЕННО:
     *   если нет явных настроек, создаём/берём BINANCE + MAINNET.
     *   Это совпадает с сигнатурой:
     *     getOrCreate(Long chatId, String exchange, NetworkType networkType)
     */
    public ExchangeClient getClientByChatId(Long chatId) {
        // TODO: позже можно подтянуть из выбранных пользователем настроек (UI),
        // сейчас — дефолт: BINANCE + MAINNET
        ExchangeSettings settings = exchangeSettingsService.getOrCreate(
                chatId,
                "BINANCE",
                NetworkType.MAINNET
        );

        return getClient(settings);
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
