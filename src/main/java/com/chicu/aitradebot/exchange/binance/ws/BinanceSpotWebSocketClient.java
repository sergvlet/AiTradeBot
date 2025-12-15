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

    /** ✅ общий OkHttpClient из Spring */
    private final OkHttpClient client;

    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();

    /** DI */
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
        String stream = (symbol + "@kline_" + timeframe).toLowerCase();

        if (sockets.containsKey(stream)) {
            log.info("📡 [BINANCE-SPOT] Already subscribed {}", stream);
            return;
        }

        String url = String.format(WS_URL_TEMPLATE, stream);
        log.info("📡 [BINANCE-SPOT] CONNECT {}", url);

        Request request = new Request.Builder()
                .url(url)
                .build();

        WebSocket ws = client.newWebSocket(
                request,
                new SpotKlineListener(stream, chatId, strategyType)
        );

        sockets.put(stream, ws);
    }

    // =====================================================================
    // UNSUBSCRIBE
    // =====================================================================

    public synchronized void unsubscribeKline(String symbol, String timeframe) {
        String stream = (symbol + "@kline_" + timeframe).toLowerCase();
        WebSocket ws = sockets.remove(stream);
        if (ws != null) {
            log.info("📡 [BINANCE-SPOT] CLOSE {}", stream);
            ws.close(1000, "client unsubscribe");
        }
    }

    public void close() {
        sockets.forEach((s, ws) -> ws.close(1000, "shutdown"));
        sockets.clear();
    }

    // =====================================================================
    // LISTENER
    // =====================================================================

    private class SpotKlineListener extends WebSocketListener {

        private final String streamName;
        private final long chatId;
        private final StrategyType strategyType;

        SpotKlineListener(String streamName, long chatId, StrategyType strategyType) {
            this.streamName = streamName;
            this.chatId = chatId;
            this.strategyType = strategyType;
        }

        @Override
        public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
            log.info("✅ [BINANCE-SPOT] WS OPEN {}", streamName);
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
            try {
                UnifiedKline kline = parser.parse(text);
                if (kline != null) {
                    marketStream.onKline(chatId, strategyType, kline);
                }
            } catch (Exception e) {
                log.error("❌ [BINANCE-SPOT] parse error: {}", e.getMessage(), e);
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
            log.error("❌ [BINANCE-SPOT] WS failure {}: {}", streamName, t.getMessage());
            sockets.remove(streamName);
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, @NotNull String reason) {
            log.warn("⚠ [BINANCE-SPOT] WS closing {}: {} / {}", streamName, code, reason);
            sockets.remove(streamName);
            webSocket.close(1000, "client closing");
        }
    }
}
