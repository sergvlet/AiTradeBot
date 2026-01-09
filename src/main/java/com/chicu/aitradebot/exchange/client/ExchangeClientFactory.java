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

    /**
     * registry:
     *   (BINANCE, MAINNET) -> BinanceExchangeClient
     *   (BINANCE, TESTNET) -> BinanceExchangeClient
     *   (BYBIT,   MAINNET) -> BybitExchangeClient
     */
    private final Map<Key, ExchangeClient> registry = new ConcurrentHashMap<>();

    // =====================================================================
    // REGISTRATION (startup only)
    // =====================================================================
    public void register(String exchange, NetworkType networkType, ExchangeClient client) {

        if (exchange == null || networkType == null || client == null) {
            throw new IllegalArgumentException("register(): exchange/network/client не могут быть null");
        }

        String ex = normalize(exchange);

        // 🔒 ЖЁСТКАЯ ПРОВЕРКА ИНВАРИАНТА
        if (!ex.equals(normalize(client.getExchangeName()))) {
            throw new IllegalStateException(
                    "❌ Несовпадение exchange: registry=" + ex +
                    ", client=" + client.getExchangeName()
            );
        }

        Key key = new Key(ex, networkType);

        ExchangeClient prev = registry.putIfAbsent(key, client);
        if (prev != null) {
            log.warn(
                    "⚠️ ExchangeClient уже зарегистрирован: {} / {} (ignored)",
                    ex, networkType
            );
            return;
        }

        log.info("🔌 ExchangeClient зарегистрирован: {} / {}", ex, networkType);
    }

    // =====================================================================
    // LOW-LEVEL GET (exchange + network)
    // =====================================================================
    public ExchangeClient get(String exchange, NetworkType networkType) {

        if (exchange == null || networkType == null) {
            throw new IllegalArgumentException("exchange/network не могут быть null");
        }

        String ex = normalize(exchange);
        Key key = new Key(ex, networkType);

        ExchangeClient client = registry.get(key);

        if (client == null) {
            throw new IllegalStateException(
                    "❌ ExchangeClient не зарегистрирован: " + ex + " / " + networkType
            );
        }

        return client;
    }

    // =====================================================================
    // 🔥 MAIN METHOD — chatId → exchange + network → client
    // =====================================================================
    public ExchangeClient getByChat(Long chatId) {

        if (chatId == null) {
            throw new IllegalArgumentException("chatId не может быть null");
        }

        List<ExchangeSettings> list =
                exchangeSettingsService.findAllByChatId(chatId);

        if (list.isEmpty()) {
            throw new IllegalStateException(
                    "❌ Нет exchange_settings для chatId=" + chatId
            );
        }

        // ✅ ENABLED + самая свежая
        ExchangeSettings settings = list.stream()

                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "❌ У пользователя нет включённых exchange settings: chatId=" + chatId
                ));

        String exchange = settings.getExchange();
        NetworkType network = settings.getNetwork();

        log.debug(
                "🔍 ExchangeClient выбран: exchange={} network={} chatId={}",
                exchange, network, chatId
        );

        return get(exchange, network);
    }

    // =====================================================================
    // HELPERS
    // =====================================================================
    private String normalize(String exchange) {
        return exchange.trim().toUpperCase(Locale.ROOT);
    }

    private record Key(String exchange, NetworkType networkType) {}
}
