package com.chicu.aitradebot.exchange.binance.ws;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.binance.parser.BinanceKlineParser;
import com.chicu.aitradebot.market.MarketStreamService;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceSpotWebSocketClient {

    private static final String WS_URL_TEMPLATE =
            "wss://stream.binance.com:9443/stream?streams=%s";

    // ✅ логируем редко, чтобы не спамить
    private static final long LOG_EVERY_N = 200;

    private final OkHttpClient client;
    private final BinanceKlineParser parser;
    private final MarketStreamService marketStream;
    private final ObjectMapper objectMapper;

    /**
     * key = chatId:strategy:symbol:timeframe[:aggTrade]
     */
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();

    /**
     * Счётчики сообщений (ключ сокета -> счётчик)
     */
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    // =====================================================================
    // SUBSCRIBE KLINE
    // =====================================================================

    public synchronized void subscribeKline(
            String symbol,
            String timeframe,
            long chatId,
            StrategyType strategyType
    ) {
        String sym = normSymbol(symbol);
        String tf = normTf(timeframe);

        String key = buildKey(sym, tf, chatId, strategyType);

        if (sockets.containsKey(key)) {
            log.debug("[BINANCE-SPOT] KLINE already subscribed {}", key);
            return;
        }

        String stream = (sym.toLowerCase(Locale.ROOT) + "@kline_" + tf).toLowerCase(Locale.ROOT);
        String url = String.format(WS_URL_TEMPLATE, stream);

        log.info("[BINANCE-SPOT] CONNECT KLINE {} (key={})", sym, key);

        Request request = new Request.Builder().url(url).build();
        WebSocket ws = client.newWebSocket(
                request,
                new SpotKlineListener(key, chatId, strategyType)
        );

        sockets.put(key, ws);
        counters.putIfAbsent(key, new AtomicLong(0));
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
        String sym = normSymbol(symbol);
        String tf = normTf(timeframe);

        String key = buildAggKey(sym, tf, chatId, strategyType);

        if (sockets.containsKey(key)) {
            log.debug("[BINANCE-SPOT] AGGTRADE already subscribed {}", key);
            return;
        }

        // ✅ ВАЖНО:
        // symbol должен быть lowercase, но "aggTrade" лучше оставить как есть (с T),
        // иначе иногда получаешь "WS OPEN" без реальных данных.
        String stream = sym.toLowerCase(Locale.ROOT) + "@aggTrade";

        String url = String.format(WS_URL_TEMPLATE, stream);

        log.info("[BINANCE-SPOT] CONNECT AGGTRADE {} (key={}) stream={}", sym, key, stream);

        Request request = new Request.Builder().url(url).build();
        WebSocket ws = client.newWebSocket(
                request,
                new SpotAggTradeListener(
                        key,
                        chatId,
                        strategyType,
                        sym,
                        tf
                )
        );

        sockets.put(key, ws);
        counters.putIfAbsent(key, new AtomicLong(0));
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
               symbol.toUpperCase(Locale.ROOT) + ":" +
               timeframe.toLowerCase(Locale.ROOT);
    }

    private String buildAggKey(
            String symbol,
            String timeframe,
            long chatId,
            StrategyType strategyType
    ) {
        return chatId + ":" +
               strategyType.name() + ":" +
               symbol.toUpperCase(Locale.ROOT) + ":" +
               timeframe.toLowerCase(Locale.ROOT) + ":aggTrade";
    }

    private static String normSymbol(String symbol) {
        if (symbol == null) return "";
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static String normTf(String timeframe) {
        if (timeframe == null) return "";
        return timeframe.trim().toLowerCase(Locale.ROOT);
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

        @Override
        public void onFailure(
                @NotNull WebSocket webSocket,
                @NotNull Throwable t,
                Response response
        ) {
            log.warn("[BINANCE-SPOT] KLINE WS failure {}: {}", key, t.getMessage(), t);
            sockets.remove(key);
            counters.remove(key);
        }

        @Override
        public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
            log.warn("[BINANCE-SPOT] KLINE WS closed {} code={} reason={}", key, code, reason);
            sockets.remove(key);
            counters.remove(key);
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

            // ✅ редкий лог: докажем, что тики реально приходят
            long n = counters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
            if (n % LOG_EVERY_N == 0) {
                String p = "?";
                String q = "?";
                String T = "?";
                String stream = "?";

                try {
                    JsonNode root = objectMapper.readTree(text);
                    if (root.hasNonNull("stream")) stream = root.get("stream").asText();
                    JsonNode data = root.has("data") ? root.get("data") : root;

                    if (data != null) {
                        if (data.hasNonNull("p")) p = data.get("p").asText();
                        if (data.hasNonNull("q")) q = data.get("q").asText();
                        if (data.hasNonNull("T")) T = data.get("T").asText();
                    }
                } catch (Exception ignore) {
                }

                log.info("📌 AGGTRADE_IN[{}] key={} sym={} tf={} stream={} p={} q={} T={}",
                        n, key, symbol, timeframe, stream, p, q, T);
            }

            try {
                // ✅ форвардим в MarketStreamService (там pushPriceTick + обновление свечи)
                marketStream.onAggTrade(chatId, strategyType, symbol, timeframe, text);

                // ✅ факт форвардинга (тоже редко, чтобы не спамить)
                if (n % LOG_EVERY_N == 0) {
                    log.info("✅ AGGTRADE forwarded → onAggTrade (pushPriceTick should happen) key={}", key);
                }

            } catch (Exception e) {
                log.error("[BINANCE-SPOT] AGGTRADE error {}: {}", key, e.getMessage(), e);
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
            log.warn("[BINANCE-SPOT] AGGTRADE WS failure {}: {}", key, t.getMessage(), t);
            sockets.remove(key);
            counters.remove(key);
        }

        @Override
        public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
            log.warn("[BINANCE-SPOT] AGGTRADE WS closed {} code={} reason={}", key, code, reason);
            sockets.remove(key);
            counters.remove(key);
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
        String sym = normSymbol(symbol);
        String tf = normTf(timeframe);

        String key = buildKey(sym, tf, chatId, strategyType);

        WebSocket ws = sockets.remove(key);
        counters.remove(key);

        if (ws != null) {
            log.info("[BINANCE-SPOT] KLINE UNSUBSCRIBE {}", key);
            ws.close(1000, "client unsubscribe kline");
        }
    }

    public synchronized void unsubscribeAggTrade(
            String symbol,
            String timeframe,
            long chatId,
            StrategyType strategyType
    ) {
        String sym = normSymbol(symbol);
        String tf = normTf(timeframe);

        String key = buildAggKey(sym, tf, chatId, strategyType);

        WebSocket ws = sockets.remove(key);
        counters.remove(key);

        if (ws != null) {
            log.info("[BINANCE-SPOT] AGGTRADE UNSUBSCRIBE {}", key);
            ws.close(1000, "client unsubscribe aggTrade");
        }
    }
}
