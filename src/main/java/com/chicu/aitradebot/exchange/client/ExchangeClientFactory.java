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

    private final Map<String, ExchangeClient> registry = new ConcurrentHashMap<>();

    public void register(String exchange, NetworkType networkType, ExchangeClient client) {
        String key = buildKey(exchange, networkType);

        if (registry.containsKey(key)) {
            log.warn("⚠️ Клиент {} уже зарегистрирован — пропускаем", key);
            return;
        }

        registry.put(key, client);
        log.info("🔹 Зарегистрирован клиент {}", key);
    }

    public ExchangeClient getClient(ExchangeSettings settings) {
        if (settings == null)
            throw new IllegalArgumentException("ExchangeSettings не может быть null");

        return getClient(settings.getExchange(), settings.getNetwork());
    }

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
     * Получить клиента по chatId (автоматический выбор настроек).
     */
    public ExchangeClient getClientByChatId(Long chatId) {

        // Берём активную биржу.
        ExchangeSettings settings = exchangeSettingsService.findAllByChatId(chatId)
                .stream()
                .filter(ExchangeSettings::isEnabled)
                .findFirst()
                .orElseGet(() ->
                        exchangeSettingsService.getOrCreate(chatId, "BINANCE", NetworkType.MAINNET)
                );

        return getClient(settings);
    }

    private String buildKey(String exchange, NetworkType networkType) {
        return exchange.toUpperCase() + "_" + networkType.name();
    }

    public boolean hasClient(String exchange, NetworkType networkType) {
        return registry.containsKey(buildKey(exchange, networkType));
    }

    public void printRegistry() {
        log.info("📋 Зарегистрированные клиенты: {}", registry.keySet());
    }
}
