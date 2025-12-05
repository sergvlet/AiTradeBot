package com.chicu.aitradebot.exchange.binance.ws;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 📡 Binance SPOT kline WebSocket клиент
 * Заменяет BinanceFuturesWebSocketClient, НО работает только со spot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceSpotWebSocketClient {

    // SPOT, НЕ фьючи
    private static final String WS_URL_TEMPLATE =
            "wss://stream.binance.com:9443/stream?streams=%s";

    private final OkHttpClient client = new OkHttpClient();

    /**
     * key: streamName (ethusdt@kline_1m)
     * value: WebSocket
     */
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();

    /**
     * Колбэк наверх (например, в CandleWebSocketHandler):
     * (streamName, rawJson)
     */
    @Setter
    private BiConsumer<String, String> messageHandler;

    // =====================================================================
    // 🔌 SUBSCRIBE KLINE
    // =====================================================================

    public synchronized void subscribeKline(String symbol, String timeframe) {
        String stream = (symbol + "@kline_" + timeframe).toLowerCase();

        if (sockets.containsKey(stream)) {
            log.info("📡 [BINANCE-SPOT] Уже подписаны на {}", stream);
            return;
        }

        String url = String.format(WS_URL_TEMPLATE, stream);
        log.info("📡 [BINANCE-SPOT] CONNECT {}", url);

        Request request = new Request.Builder()
                .url(url)
                .build();

        WebSocket ws = client.newWebSocket(request, new SpotKlineListener(stream));
        sockets.put(stream, ws);
    }

    // Отписка (по желанию можно вызывать из CandleWebSocketHandler#afterConnectionClosed)
    public synchronized void unsubscribeKline(String symbol, String timeframe) {
        String stream = (symbol + "@kline_" + timeframe).toLowerCase();
        WebSocket ws = sockets.remove(stream);
        if (ws != null) {
            log.info("📡 [BINANCE-SPOT] CLOSE {}", stream);
            ws.close(1000, "client unsubscribe");
        }
    }

    public synchronized void closeAll() {
        sockets.forEach((stream, ws) -> {
            try {
                ws.close(1000, "shutdown");
            } catch (Exception ignored) {}
        });
        sockets.clear();
    }

    // =====================================================================
    // 🧠 LISTENER
    // =====================================================================

    private class SpotKlineListener extends WebSocketListener {

        private final String streamName;

        private SpotKlineListener(String streamName) {
            this.streamName = streamName;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            log.info("✅ [BINANCE-SPOT] WS OPEN {}", streamName);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                // типичный формат: { "stream": "ethusdt@kline_1m", "data": { ... } }
                if (messageHandler != null) {
                    messageHandler.accept(streamName, text);
                }
            } catch (Exception e) {
                log.error("❌ [BINANCE-SPOT] parse error: {}", e.getMessage(), e);
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            onMessage(webSocket, bytes.utf8());
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            log.warn("⚠️ [BINANCE-SPOT] WS closing {}: {} / {}", streamName, code, reason);
            webSocket.close(1000, null);
            sockets.remove(streamName);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            log.error("❌ [BINANCE-SPOT] WS failure {}: {}", streamName, t.getMessage(), t);
            sockets.remove(streamName);
        }
    }
}
