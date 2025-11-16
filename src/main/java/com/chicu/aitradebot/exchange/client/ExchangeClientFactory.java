package com.chicu.aitradebot.exchange;

import com.chicu.aitradebot.common.enums.NetworkType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ExchangeClientFactory — фабрика, возвращающая нужный клиент по бирже и сети.
 */
@Component
@RequiredArgsConstructor
public class ExchangeClientFactory {

    private final List<ExchangeClient> allClients;
    private final Map<String, ExchangeClient> registry = new HashMap<>();

    @PostConstruct
    public void init() {
        for (ExchangeClient client : allClients) {
            String key = key(client.getExchangeName(), client.getNetworkType());
            registry.put(key, client);
        }
    }

    public ExchangeClient get(String exchange, NetworkType network) {
        ExchangeClient client = registry.get(key(exchange, network));
        if (client == null) {
            throw new IllegalArgumentException("❌ Клиент не найден: " + exchange + " / " + network);
        }
        return client;
    }

    private String key(String exchange, NetworkType network) {
        return exchange.toUpperCase() + "|" + network.name();
    }
}
