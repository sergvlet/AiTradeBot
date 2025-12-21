package com.chicu.aitradebot.exchange.binance.ws;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.binance.parser.BinanceKlineParser;
import com.chicu.aitradebot.market.MarketStreamService;
import com.chicu.aitradebot.market.model.UnifiedKline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceSpotWebSocketClient {

    private static final String WS_URL_TEMPLATE =
            "wss://stream.binance.com:9443/stream?streams=%s";

    /** Общий OkHttpClient из Spring */
    private final OkHttpClient client;

    /**
     * 🔑 V4 ключ:
     * chatId:strategyType:symbol:timeframe
     */
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();

    private final BinanceKlineParser parser;
    private final MarketStreamService marketStream;

    // =====================================================================
    // SUBSCRIBE
    // =====================================================================

    public synchronized void subscribeKline(
            String symbol,
            String timeframe,
            long chatId,
            StrategyType strategyType
    ) {

        String key = buildKey(symbol, timeframe, chatId, strategyType);

        if (sockets.containsKey(key)) {
            log.info("📡 [BINANCE-SPOT] Already subscribed {}", key);
            return;
        }

        String stream = (symbol + "@kline_" + timeframe).toLowerCase();
        String url = String.format(WS_URL_TEMPLATE, stream);

        log.info("📡 [BINANCE-SPOT] CONNECT {} (key={})", url, key);

        Request request = new Request.Builder()
                .url(url)
                .build();

        WebSocket ws = client.newWebSocket(
                request,
                new SpotKlineListener(key, chatId, strategyType)
        );

        sockets.put(key, ws);
    }

    // =====================================================================
    // UNSUBSCRIBE
    // =====================================================================

    public synchronized void unsubscribeKline(
            String symbol,
            String timeframe,
            long chatId,
            StrategyType strategyType
    ) {

        String key = buildKey(symbol, timeframe, chatId, strategyType);
        WebSocket ws = sockets.remove(key);

        if (ws != null) {
            log.info("🔌 [BINANCE-SPOT] CLOSE {}", key);
            ws.close(1000, "client unsubscribe");
        }
    }

    public void closeAll() {
        sockets.forEach((k, ws) -> {
            try {
                ws.close(1000, "shutdown");
            } catch (Exception ignored) {}
        });
        sockets.clear();
    }

    // =====================================================================
    // KEY BUILDER
    // =====================================================================

    private String buildKey(
            String symbol,
            String timeframe,
            long chatId,
            StrategyType strategyType
    ) {
        return chatId + ":"
               + strategyType.name() + ":"
               + symbol.toUpperCase() + ":"
               + timeframe.toLowerCase();
    }

    // =====================================================================
    // LISTENER
    // =====================================================================

    private class SpotKlineListener extends WebSocketListener {

        private final String key;
        private final long chatId;
        private final StrategyType strategyType;

        SpotKlineListener(String key, long chatId, StrategyType strategyType) {
            this.key = key;
            this.chatId = chatId;
            this.strategyType = strategyType;
        }

        @Override
        public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
            log.info("✅ [BINANCE-SPOT] WS OPEN {}", key);
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
            try {
                UnifiedKline kline = parser.parse(text);
                if (kline != null) {
                    marketStream.onKline(chatId, strategyType, kline);
                }
            } catch (Exception e) {
                log.error("❌ [BINANCE-SPOT] parse error {}: {}", key, e.getMessage(), e);
            }
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
            onMessage(webSocket, bytes.utf8());
        }

        @Override
        public void onFailure(
                @NotNull WebSocket webSocket,
                @NotNull Throwable t,
                Response response
        ) {
            log.error("❌ [BINANCE-SPOT] WS failure {}: {}", key, t.getMessage());
            sockets.remove(key);
        }

        @Override
        public void onClosing(
                @NotNull WebSocket webSocket,
                int code,
                @NotNull String reason
        ) {
            log.warn("⚠ [BINANCE-SPOT] WS closing {}: {} / {}", key, code, reason);
            sockets.remove(key);
            webSocket.close(1000, "client closing");
        }
    }
}
