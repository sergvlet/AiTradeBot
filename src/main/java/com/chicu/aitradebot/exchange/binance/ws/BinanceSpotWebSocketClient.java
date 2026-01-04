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

    private final OkHttpClient client;
    private final BinanceKlineParser parser;
    private final MarketStreamService marketStream;

    /**
     * key = chatId:strategy:symbol:timeframe[:aggTrade]
     */
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();

    // =====================================================================
    // SUBSCRIBE KLINE
    // =====================================================================

    public synchronized void subscribeKline(
            String symbol,
            String timeframe,
            long chatId,
            StrategyType strategyType
    ) {
        String key = buildKey(symbol, timeframe, chatId, strategyType);

        if (sockets.containsKey(key)) {
            log.debug("[BINANCE-SPOT] KLINE already subscribed {}", key);
            return;
        }

        String stream = (symbol + "@kline_" + timeframe).toLowerCase();
        String url = String.format(WS_URL_TEMPLATE, stream);

        log.info("[BINANCE-SPOT] CONNECT KLINE {} (key={})", symbol, key);

        Request request = new Request.Builder().url(url).build();
        WebSocket ws = client.newWebSocket(
                request,
                new SpotKlineListener(key, chatId, strategyType)
        );

        sockets.put(key, ws);
    }

    // =====================================================================
    // SUBSCRIBE AGG TRADE
    // =====================================================================

    public synchronized void subscribeAggTrade(
            String symbol,
            String timeframe,
            long chatId,
            StrategyType strategyType
    ) {
        String key = chatId + ":" + strategyType.name() + ":" +
                     symbol.toUpperCase() + ":" + timeframe.toLowerCase() + ":aggTrade";

        if (sockets.containsKey(key)) {
            log.debug("[BINANCE-SPOT] AGGTRADE already subscribed {}", key);
            return;
        }

        String stream = (symbol + "@aggTrade").toLowerCase();
        String url = String.format(WS_URL_TEMPLATE, stream);

        log.info("[BINANCE-SPOT] CONNECT AGGTRADE {} (key={})", symbol, key);

        Request request = new Request.Builder().url(url).build();
        WebSocket ws = client.newWebSocket(
                request,
                new SpotAggTradeListener(
                        key,
                        chatId,
                        strategyType,
                        symbol.toUpperCase(),
                        timeframe.toLowerCase()
                )
        );

        sockets.put(key, ws);
    }

    // =====================================================================
    // KEY
    // =====================================================================

    private String buildKey(
            String symbol,
            String timeframe,
            long chatId,
            StrategyType strategyType
    ) {
        return chatId + ":" +
               strategyType.name() + ":" +
               symbol.toUpperCase() + ":" +
               timeframe.toLowerCase();
    }

    // =====================================================================
    // KLINE LISTENER
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
            log.info("[BINANCE-SPOT] KLINE WS OPEN {}", key);
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {

            // 🔍 RAW сообщения — ТОЛЬКО для диагностики
            if (log.isTraceEnabled()) {
                log.trace("[BINANCE-SPOT] RAW KLINE {} => {}", key, text);
            }

            try {
                UnifiedKline kline = parser.parse(text);
                if (kline == null) return;

                marketStream.onKline(chatId, strategyType, kline);

                if (kline.isClosed()) {
                    marketStream.closeCandle(chatId, kline);
                }

            } catch (Exception e) {
                log.error("[BINANCE-SPOT] KLINE parse error {}: {}", key, e.getMessage(), e);
            }
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
            onMessage(webSocket, bytes.utf8());
        }
    }

    // =====================================================================
    // AGG TRADE LISTENER
    // =====================================================================

    private class SpotAggTradeListener extends WebSocketListener {

        private final String key;
        private final long chatId;
        private final StrategyType strategyType;
        private final String symbol;
        private final String timeframe;

        SpotAggTradeListener(
                String key,
                long chatId,
                StrategyType strategyType,
                String symbol,
                String timeframe
        ) {
            this.key = key;
            this.chatId = chatId;
            this.strategyType = strategyType;
            this.symbol = symbol;
            this.timeframe = timeframe;
        }

        @Override
        public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
            log.info("[BINANCE-SPOT] AGGTRADE WS OPEN {}", key);
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {

            if (log.isTraceEnabled()) {
                log.trace("[BINANCE-SPOT] RAW AGGTRADE {} => {}", key, text);
            }

            try {
                marketStream.onAggTrade(
                        chatId,
                        strategyType,
                        symbol,
                        timeframe,
                        text
                );
            } catch (Exception e) {
                log.error("[BINANCE-SPOT] AGGTRADE error {}: {}", key, e.getMessage(), e);
            }
        }

        @Override
        public void onFailure(
                @NotNull WebSocket webSocket,
                @NotNull Throwable t,
                Response response
        ) {
            log.error("[BINANCE-SPOT] AGGTRADE WS failure {}: {}", key, t.getMessage(), t);
            sockets.remove(key);
        }
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
            log.info("[BINANCE-SPOT] KLINE UNSUBSCRIBE {}", key);
            ws.close(1000, "client unsubscribe kline");
        }
    }
}
