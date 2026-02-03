package com.chicu.aitradebot.exchange.binance.ws;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.parser.BinanceKlineParser;
import com.chicu.aitradebot.market.MarketStreamService;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceSpotWebSocketClient {

    /**
     * MAINNET (SPOT)
     */
    private static final String WS_URL_MAINNET =
            "wss://stream.binance.com:9443/stream?streams=%s";

    /**
     * TESTNET/DEMO (SPOT Demo Mode)
     * Важно: testnet.binance.vision не отдаёт нормальный WS upgrade (получишь 404).
     */
    private static final String WS_URL_TESTNET =
            "wss://demo-stream.binance.com:9443/stream?streams=%s";

    private static final long LOG_EVERY_N = 200;

    private final OkHttpClient client;
    private final BinanceKlineParser parser;
    private final MarketStreamService marketStream;
    private final ObjectMapper objectMapper;

    /**
     * key = chatId:strategy:EXCHANGE:NETWORK:symbol:timeframe[:aggTrade]
     */
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    // =====================================================================
    // SUBSCRIBE KLINE
    // =====================================================================

    public synchronized void subscribeKline(
            NetworkType networkType,
            String symbol,
            String timeframe,
            long chatId,
            StrategyType strategyType
    ) {
        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        if (sym == null || tf == null || networkType == null || strategyType == null) {
            log.warn("⚠️ [BINANCE] subscribeKline пропуск: chatId={} type={} net={} sym='{}' tf='{}'",
                    chatId, strategyType, networkType, symbol, timeframe);
            return;
        }

        String key = buildKey(chatId, strategyType, "BINANCE", networkType, sym, tf);

        WebSocket existing = sockets.get(key);

        // ✅ если сокет есть и счётчик есть — значит уже подписаны
        if (existing != null && counters.containsKey(key)) {
            log.debug("⏭ [BINANCE] KLINE уже подписан {}", key);
            return;
        }

        // ✅ если сокет есть, а счётчика нет — состояние битое (например, после failure) -> закрываем и пересоздаём
        if (existing != null) {
            try { existing.close(1000, "recreate broken state"); } catch (Exception ignored) {}
            sockets.remove(key);
            counters.remove(key);
        }

        String stream = buildKlineStream(sym, tf);
        String url = String.format(resolveWsTemplate(networkType), stream);

        log.info("🔌 [BINANCE] CONNECT KLINE key={} net={} stream={}", key, networkType, stream);

        Request request = new Request.Builder().url(url).build();
        WebSocket ws = client.newWebSocket(
                request,
                new SpotKlineListener(key, chatId, strategyType, networkType, sym, tf)
        );

        sockets.put(key, ws);
        counters.put(key, new AtomicLong(0));
    }

    // =====================================================================
    // SUBSCRIBE AGG TRADE
    // =====================================================================

    public synchronized void subscribeAggTrade(
            NetworkType networkType,
            String symbol,
            String timeframe,
            long chatId,
            StrategyType strategyType
    ) {
        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        if (sym == null || tf == null || networkType == null || strategyType == null) {
            log.warn("⚠️ [BINANCE] subscribeAggTrade пропуск: chatId={} type={} net={} sym='{}' tf='{}'",
                    chatId, strategyType, networkType, symbol, timeframe);
            return;
        }

        String key = buildAggKey(chatId, strategyType, "BINANCE", networkType, sym, tf);

        WebSocket existing = sockets.get(key);

        if (existing != null && counters.containsKey(key)) {
            log.debug("⏭ [BINANCE] AGGTRADE уже подписан {}", key);
            return;
        }

        // битое состояние — чистим и создаём заново
        if (existing != null) {
            try { existing.close(1000, "recreate broken state"); } catch (Exception ignored) {}
            sockets.remove(key);
            counters.remove(key);
        }

        String stream = buildAggTradeStream(sym);
        String url = String.format(resolveWsTemplate(networkType), stream);

        log.info("🔌 [BINANCE] CONNECT AGGTRADE key={} net={} stream={}", key, networkType, stream);

        Request request = new Request.Builder().url(url).build();
        WebSocket ws = client.newWebSocket(
                request,
                new SpotAggTradeListener(key, chatId, strategyType, networkType, sym, tf)
        );

        sockets.put(key, ws);
        counters.put(key, new AtomicLong(0));
    }

    // =====================================================================
    // UNSUBSCRIBE
    // =====================================================================

    public synchronized void unsubscribeKline(
            NetworkType networkType,
            String symbol,
            String timeframe,
            long chatId,
            StrategyType strategyType
    ) {
        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        if (sym == null || tf == null || networkType == null || strategyType == null) return;

        String key = buildKey(chatId, strategyType, "BINANCE", networkType, sym, tf);

        WebSocket ws = sockets.remove(key);
        counters.remove(key);

        if (ws != null) {
            log.info("🧹 [BINANCE] KLINE UNSUBSCRIBE {}", key);
            try { ws.close(1000, "client unsubscribe kline"); } catch (Exception ignored) {}
        }
    }

    public synchronized void unsubscribeAggTrade(
            NetworkType networkType,
            String symbol,
            String timeframe,
            long chatId,
            StrategyType strategyType
    ) {
        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        if (sym == null || tf == null || networkType == null || strategyType == null) return;

        String key = buildAggKey(chatId, strategyType, "BINANCE", networkType, sym, tf);

        WebSocket ws = sockets.remove(key);
        counters.remove(key);

        if (ws != null) {
            log.info("🧹 [BINANCE] AGGTRADE UNSUBSCRIBE {}", key);
            try { ws.close(1000, "client unsubscribe aggTrade"); } catch (Exception ignored) {}
        }
    }

    // =====================================================================
    // LISTENERS
    // =====================================================================

    private class SpotKlineListener extends WebSocketListener {

        private final String key;
        private final long chatId;
        private final StrategyType strategyType;
        private final NetworkType networkType;

        /** ✅ уже нормализованные */
        private final String sym;
        private final String tf;

        SpotKlineListener(String key,
                          long chatId,
                          StrategyType strategyType,
                          NetworkType networkType,
                          String sym,
                          String tf) {
            this.key = key;
            this.chatId = chatId;
            this.strategyType = strategyType;
            this.networkType = networkType;
            this.sym = sym;
            this.tf = tf;
        }

        @Override
        public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
            log.info("🟢 [BINANCE] KLINE WS OPEN {} (net={} http={})", key, networkType, response.code());
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {

            long n = counters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();

            String stream = "?";
            JSONObject dataObj;

            try {
                JsonNode root = objectMapper.readTree(text);
                if (root.hasNonNull("stream")) stream = root.get("stream").asText();

                JsonNode data = root.hasNonNull("data") ? root.get("data") : root;
                dataObj = new JSONObject(data.toString());

            } catch (Exception e) {
                try {
                    dataObj = new JSONObject(text);
                } catch (Exception ex) {
                    if (n % LOG_EVERY_N == 0) {
                        log.warn("⚠️ [BINANCE] KLINE_IN[{}] key={} stream={} плохой json: {}",
                                n, key, stream, ex.getMessage());
                    }
                    return;
                }
            }

            if (n == 1) {
                log.info("🟢 [BINANCE] KLINE FIRST MSG key={} net={} sym={} tf={} stream={}",
                        key, networkType, sym, tf, stream);
            }

            if (n % LOG_EVERY_N == 0) {
                log.info("📌 [BINANCE] KLINE_IN[{}] key={} net={} sym={} tf={} stream={}",
                        n, key, networkType, sym, tf, stream);
            }

            try {
                UnifiedKline kline;

                try {
                    kline = parser.parse(dataObj);
                } catch (Exception first) {
                    if (dataObj.has("k") && dataObj.get("k") instanceof JSONObject kObj) {
                        kline = parser.parse(kObj);
                    } else {
                        throw first;
                    }
                }

                if (kline == null) return;

                // ✅ гарантируем, что kline заполнен (на всякий)
                if (kline.getSymbol() == null || kline.getSymbol().isBlank()) kline.setSymbol(sym);
                if (kline.getTimeframe() == null || kline.getTimeframe().isBlank()) kline.setTimeframe(tf);

                // ✅ СТРОГО: symbol+timeframe в контексте
                marketStream.onKline(chatId, strategyType, "BINANCE", networkType, sym, tf, kline);

            } catch (Exception e) {
                log.error("❌ [BINANCE] KLINE parse error key={} : {}", key, e.getMessage(), e);
            }
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
            onMessage(webSocket, bytes.utf8());
        }

        @Override
        public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, Response response) {
            String resp = response != null ? (response.code() + " " + response.message()) : "null";
            log.warn("⚠️ [BINANCE] KLINE WS failure {} (resp={}): {}", key, resp, t.getMessage(), t);

            sockets.remove(key, webSocket);
            counters.remove(key);

            try { webSocket.cancel(); } catch (Exception ignored) {}
        }

        @Override
        public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
            log.warn("⚠️ [BINANCE] KLINE WS closed {} code={} reason={}", key, code, reason);

            sockets.remove(key, webSocket);
            counters.remove(key);
        }
    }

    private class SpotAggTradeListener extends WebSocketListener {

        private final String key;
        private final long chatId;
        private final StrategyType strategyType;
        private final NetworkType networkType;

        /** ✅ уже нормализованные */
        private final String sym;
        private final String tf;

        SpotAggTradeListener(String key,
                             long chatId,
                             StrategyType strategyType,
                             NetworkType networkType,
                             String sym,
                             String tf) {
            this.key = key;
            this.chatId = chatId;
            this.strategyType = strategyType;
            this.networkType = networkType;
            this.sym = sym;
            this.tf = tf;
        }

        @Override
        public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
            log.info("🟢 [BINANCE] AGGTRADE WS OPEN {} (net={} http={})", key, networkType, response.code());
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {

            long n = counters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();

            BigDecimal price = null;
            BigDecimal qty = null;
            long tradeTsMs = 0L;

            String stream = "?";
            String pStr = "?";
            String qStr = "?";
            String tStr = "?";

            try {
                JsonNode root = objectMapper.readTree(text);
                if (root.hasNonNull("stream")) stream = root.get("stream").asText();

                JsonNode data = root.has("data") ? root.get("data") : root;
                if (data != null) {
                    if (data.hasNonNull("p")) {
                        pStr = data.get("p").asText();
                        price = new BigDecimal(pStr);
                    }
                    if (data.hasNonNull("q")) {
                        qStr = data.get("q").asText();
                        qty = new BigDecimal(qStr);
                    }
                    if (data.hasNonNull("T")) {
                        tStr = data.get("T").asText();
                        tradeTsMs = data.get("T").asLong();
                    }
                    if (tradeTsMs <= 0 && data.hasNonNull("E")) {
                        tradeTsMs = data.get("E").asLong();
                    }
                }
            } catch (Exception ignored) {
                // ниже общий выход по price/tradeTsMs
            }

            if (n % LOG_EVERY_N == 0) {
                log.info("📌 [BINANCE] AGGTRADE_IN[{}] key={} net={} sym={} tf={} stream={} p={} q={} T={}",
                        n, key, networkType, sym, tf, stream, pStr, qStr, tStr);
            }

            try {
                if (price == null || tradeTsMs <= 0) return;

                marketStream.onAggTrade(
                        chatId,
                        strategyType,
                        "BINANCE",
                        networkType,
                        sym,
                        tf,
                        price,
                        qty,
                        tradeTsMs
                );

            } catch (Exception e) {
                log.error("❌ [BINANCE] AGGTRADE error key={} : {}", key, e.getMessage(), e);
            }
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
            onMessage(webSocket, bytes.utf8());
        }

        @Override
        public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, Response response) {
            String resp = response != null ? (response.code() + " " + response.message()) : "null";
            log.warn("⚠️ [BINANCE] AGGTRADE WS failure {} (resp={}): {}", key, resp, t.getMessage(), t);

            sockets.remove(key, webSocket);
            counters.remove(key);

            try { webSocket.cancel(); } catch (Exception ignored) {}
        }

        @Override
        public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
            log.warn("⚠️ [BINANCE] AGGTRADE WS closed {} code={} reason={}", key, code, reason);

            sockets.remove(key, webSocket);
            counters.remove(key);
        }
    }

    // =====================================================================
    // utils
    // =====================================================================

    private static String resolveWsTemplate(NetworkType networkType) {
        return networkType == NetworkType.TESTNET ? WS_URL_TESTNET : WS_URL_MAINNET;
    }

    private static String buildKlineStream(String sym, String tf) {
        return sym.toLowerCase(Locale.ROOT) + "@kline_" + tf.toLowerCase(Locale.ROOT);
    }

    private static String buildAggTradeStream(String sym) {
        return sym.toLowerCase(Locale.ROOT) + "@aggTrade";
    }

    private static String buildKey(long chatId,
                                   StrategyType strategyType,
                                   String exchange,
                                   NetworkType networkType,
                                   String symbol,
                                   String timeframe) {
        return chatId + ":" +
               strategyType.name() + ":" +
               exchange.toUpperCase(Locale.ROOT) + ":" +
               networkType.name() + ":" +
               symbol.toUpperCase(Locale.ROOT) + ":" +
               timeframe.toLowerCase(Locale.ROOT);
    }

    private static String buildAggKey(long chatId,
                                      StrategyType strategyType,
                                      String exchange,
                                      NetworkType networkType,
                                      String symbol,
                                      String timeframe) {
        return buildKey(chatId, strategyType, exchange, networkType, symbol, timeframe) + ":aggTrade";
    }

    private static String normSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normTf(String timeframe) {
        if (timeframe == null) return null;
        String s = timeframe.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }
}
