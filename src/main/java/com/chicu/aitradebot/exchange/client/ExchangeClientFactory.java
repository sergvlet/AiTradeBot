package com.chicu.aitradebot.exchange.client;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeClientFactory {

    private final ExchangeSettingsService exchangeSettingsService;

    private final Map<Key, ExchangeClient> registry = new ConcurrentHashMap<>();

    // ============================
    // РЕГИСТРАЦИЯ
    // ============================
    public void register(String exchange, NetworkType networkType, ExchangeClient client) {
        String normalized = normalize(exchange);
        Key key = new Key(normalized, networkType);
        registry.put(key, client);
        log.info("🔌 Зарегистрирован клиент: {} / {}", normalized, networkType);
    }

    // ============================
    // ПОЛУЧЕНИЕ ПО EXCHANGE + NETWORK
    // ============================
    public ExchangeClient get(String exchange, NetworkType networkType) {
        String normalized = normalize(exchange);
        Key key = new Key(normalized, networkType);

        ExchangeClient client = registry.get(key);
        if (client == null) {
            throw new IllegalStateException(
                    "❌ Клиент не зарегистрирован: " + normalized + " / " + networkType
            );
        }
        return client;
    }

    // ============================
    // ПРАВИЛЬНЫЙ ВЫБОР ПО chatId
    // ============================
    public ExchangeClient getByChat(Long chatId) {

        if (chatId == null) {
            throw new IllegalArgumentException("chatId не может быть null");
        }

        // Берём ВСЕ записи для пользователя
        List<ExchangeSettings> list = exchangeSettingsService.findAllByChatId(chatId);

        if (list.isEmpty()) {
            throw new IllegalStateException("❌ Нет exchange_settings для chatId=" + chatId);
        }

        // 🔥 Выбираем enabled + последнюю по updatedAt
        ExchangeSettings settings = list.stream()
                .filter(ExchangeSettings::isEnabled)
                .sorted((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "❌ У пользователя нет включённых exchange settings: chatId=" + chatId
                ));

        String exchange = settings.getExchange();
        NetworkType network = settings.getNetwork();

        log.debug("🔍 Выбран профиль биржи: exchange={} network={} (chatId={})",
                exchange, network, chatId);

        return get(exchange, network);
    }


    // ============================
    // ВСПОМОГАТЕЛЬНЫЕ
    // ============================
    private String normalize(String exchange) {
        return exchange.trim().toUpperCase(Locale.ROOT);
    }

    private record Key(String exchange, NetworkType networkType) {}
}
