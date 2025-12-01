package com.chicu.aitradebot.exchange.binance.ws;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Component
public class BinanceFuturesWebSocketClient {

    private final OkHttpClient client = new OkHttpClient();

    private final Map<String, WebSocket> active = new ConcurrentHashMap<>();
    private final Map<String, Consumer<String>> listeners = new ConcurrentHashMap<>();

    public void subscribe(String key, String symbol, String timeframe, Consumer<String> handler) {

        if (active.containsKey(key)) {
            listeners.put(key, handler);
            return;
        }

        String stream = symbol.toLowerCase() + "@kline_" + timeframe;
        String url = "wss://fstream.binance.com/stream?streams=" + stream;

        log.info("📡 CONNECT {}", url);

        Request request = new Request.Builder().url(url).build();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                log.info("🌐 WS OPEN {}", url);
            }

            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                Consumer<String> cb = listeners.get(key);
                if (cb != null) cb.accept(text);

            }

            @Override
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, Response response) {
                log.error("❌ WS ERROR {} → {}", url, t.getMessage());
                active.remove(key);
            }
        });

        active.put(key, ws);
        listeners.put(key, handler);
    }
}
