package com.chicu.aitradebot.exchange.binance.ws;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.parser.BinanceKlineParser;
import com.chicu.aitradebot.market.MarketStreamService;
import com.chicu.aitradebot.market.model.UnifiedKline;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceSpotWebSocketClient {

    // ✅ MAINNET
    private static final String WS_MAIN_STREAM_TEMPLATE =
            "wss://stream.binance.com:9443/stream?streams=%s";

    // ✅ TESTNET
    private static final String WS_TEST_STREAM_TEMPLATE =
            "wss://stream.testnet.binance.vision/stream?streams=%s";

    private final OkHttpClient client;
    private final BinanceKlineParser klineParser;
    private final MarketStreamService marketStream;

    /**
     * key = BINANCE:NET:chatId:TYPE:symbol:tf:CHANNEL
     */
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();

    // =====================================================
    // PUBLIC API (используется твоим MarketDataStreamService)
    // =====================================================

    public void subscribeAggTrade(NetworkType networkType,
                                  String symbol,
                                  String timeframe,
                                  long chatId,
                                  StrategyType strategyType) {
        NetworkType net = (networkType != null) ? networkType : NetworkType.MAINNET;

        String sym = normSymbolLower(symbol);
        String tf  = normTfLower(timeframe);

        String key = buildKey(chatId, strategyType, sym, tf, net, "AGG_TRADE");
        if (sockets.containsKey(key)) return;

        String streams = sym + "@aggTrade";
        String wsUrl = buildWsUrl(net, streams);

        Request request = new Request.Builder().url(wsUrl).build();

        log.info("🔌 WS CONNECT BINANCE chatId={} type={} sym={} tf={} net={} channel=AGG_TRADE url={}",
                chatId, strategyType, sym.toUpperCase(Locale.ROOT), tf, net, wsUrl);

        WebSocket ws = client.newWebSocket(request,
                new AggTradeListener(key, chatId, strategyType, sym, tf, net));

        sockets.put(key, ws);
    }

    public void unsubscribeAggTrade(NetworkType networkType,
                                    String symbol,
                                    String timeframe,
                                    long chatId,
                                    StrategyType strategyType) {
        NetworkType net = (networkType != null) ? networkType : NetworkType.MAINNET;

        String sym = normSymbolLower(symbol);
        String tf  = normTfLower(timeframe);

        String key = buildKey(chatId, strategyType, sym, tf, net, "AGG_TRADE");
        closeAndRemove(key, "unsubscribe");
    }

    public void subscribeKline(NetworkType networkType,
                               String symbol,
                               String timeframe,
                               long chatId,
                               StrategyType strategyType) {
        NetworkType net = (networkType != null) ? networkType : NetworkType.MAINNET;

        String sym = normSymbolLower(symbol);
        String tf  = normTfLower(timeframe);

        String key = buildKey(chatId, strategyType, sym, tf, net, "KLINE");
        if (sockets.containsKey(key)) return;

        String streams = sym + "@kline_" + tf;
        String wsUrl = buildWsUrl(net, streams);

        Request request = new Request.Builder().url(wsUrl).build();

        log.info("🔌 WS CONNECT BINANCE chatId={} type={} sym={} tf={} net={} channel=KLINE url={}",
                chatId, strategyType, sym.toUpperCase(Locale.ROOT), tf, net, wsUrl);

        WebSocket ws = client.newWebSocket(request,
                new KlineListener(key, chatId, strategyType, sym, tf, net));

        sockets.put(key, ws);
    }

    public void unsubscribeKline(NetworkType networkType,
                                 String symbol,
                                 String timeframe,
                                 long chatId,
                                 StrategyType strategyType) {
        NetworkType net = (networkType != null) ? networkType : NetworkType.MAINNET;

        String sym = normSymbolLower(symbol);
        String tf  = normTfLower(timeframe);

        String key = buildKey(chatId, strategyType, sym, tf, net, "KLINE");
        closeAndRemove(key, "unsubscribe");
    }

    // =====================================================
    // LISTENERS
    // =====================================================

    private final class AggTradeListener extends WebSocketListener {
        private final String key;
        private final long chatId;
        private final StrategyType type;
        private final String symLower;
        private final String tfLower;
        private final NetworkType net;

        private AggTradeListener(String key,
                                 long chatId,
                                 StrategyType type,
                                 String symLower,
                                 String tfLower,
                                 NetworkType net) {
            this.key = key;
            this.chatId = chatId;
            this.type = type;
            this.symLower = symLower;
            this.tfLower = tfLower;
            this.net = net;
        }

        @Override
        public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
            log.info("✅ WS OPEN BINANCE key={}", key);
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
            handleAggTrade(text);
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
            handleAggTrade(bytes.utf8());
        }

        @Override
        public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
            log.warn("⚠️ WS CLOSING BINANCE key={} code={} reason={}", key, code, reason);
        }

        @Override
        public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
            log.warn("❌ WS CLOSED BINANCE key={} code={} reason={}", key, code, reason);
            sockets.remove(key);
        }

        @Override
        public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, Response response) {
            String resp = (response != null) ? (response.code() + " " + response.message()) : "no-response";
            log.error("💥 WS FAIL BINANCE key={} resp={} err={}", key, resp, t.toString());
            sockets.remove(key);
        }

        private void handleAggTrade(String raw) {
            try {
                JSONObject root = new JSONObject(raw);
                JSONObject data = root.has("data") ? root.getJSONObject("data") : root;

                String eventType = data.optString("e", "");
                if (!"aggTrade".equalsIgnoreCase(eventType)) return;

                String symUpper = symLower.toUpperCase(Locale.ROOT);

                BigDecimal price = new BigDecimal(data.getString("p"));
                BigDecimal qty   = new BigDecimal(data.getString("q"));

                // Binance aggTrade: T = trade time
                long ts = data.has("T") ? data.getLong("T")
                        : (data.has("E") ? data.getLong("E") : System.currentTimeMillis());

                marketStream.onAggTrade(
                        chatId,
                        type,
                        "BINANCE",
                        net,
                        symUpper,
                        tfLower,
                        price,
                        qty,
                        ts
                );

            } catch (Exception e) {
                if (log.isDebugEnabled()) log.debug("WS aggTrade parse error: {}", e.toString());
            }
        }
    }

    private final class KlineListener extends WebSocketListener {
        private final String key;
        private final long chatId;
        private final StrategyType type;
        private final String symLower;
        private final String tfLower;
        private final NetworkType net;

        private KlineListener(String key,
                              long chatId,
                              StrategyType type,
                              String symLower,
                              String tfLower,
                              NetworkType net) {
            this.key = key;
            this.chatId = chatId;
            this.type = type;
            this.symLower = symLower;
            this.tfLower = tfLower;
            this.net = net;
        }

        @Override
        public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
            log.info("✅ WS OPEN BINANCE key={}", key);
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
            handleKline(text);
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
            handleKline(bytes.utf8());
        }

        @Override
        public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
            log.warn("⚠️ WS CLOSING BINANCE key={} code={} reason={}", key, code, reason);
        }

        @Override
        public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
            log.warn("❌ WS CLOSED BINANCE key={} code={} reason={}", key, code, reason);
            sockets.remove(key);
        }

        @Override
        public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, Response response) {
            String resp = (response != null) ? (response.code() + " " + response.message()) : "no-response";
            log.error("💥 WS FAIL BINANCE key={} resp={} err={}", key, resp, t.toString());
            sockets.remove(key);
        }

        private void handleKline(String raw) {
            try {
                JSONObject root = new JSONObject(raw);
                JSONObject data = root.has("data") ? root.getJSONObject("data") : root;

                String eventType = data.optString("e", "");
                if (!"kline".equalsIgnoreCase(eventType)) return;

                UnifiedKline kline = klineParser.parse(data);
                if (kline == null) return;

                String symUpper = symLower.toUpperCase(Locale.ROOT);

                marketStream.onKline(
                        chatId,
                        type,
                        "BINANCE",
                        net,
                        symUpper,
                        tfLower,
                        kline
                );

            } catch (Exception e) {
                if (log.isDebugEnabled()) log.debug("WS kline parse error: {}", e.toString());
            }
        }
    }

    // =====================================================
    // INTERNAL
    // =====================================================

    private void closeAndRemove(String key, String reason) {
        WebSocket ws = sockets.remove(key);
        if (ws == null) return;

        try {
            ws.close(1000, reason);
        } catch (Exception ignored) {
        }

        log.info("🔌 WS DISCONNECT BINANCE key={} reason={}", key, reason);
    }

    private static String buildKey(long chatId,
                                   StrategyType strategyType,
                                   String symLower,
                                   String tfLower,
                                   NetworkType net,
                                   String channel) {
        return "BINANCE:" + net + ":" + chatId + ":" + strategyType.name() + ":" + symLower + ":" + tfLower + ":" + channel;
    }

    private static String buildWsUrl(NetworkType networkType, String streams) {
        String tpl = (networkType == NetworkType.TESTNET) ? WS_TEST_STREAM_TEMPLATE : WS_MAIN_STREAM_TEMPLATE;
        return String.format(tpl, streams);
    }

    private static String normSymbolLower(String symbol) {
        if (symbol == null) throw new IllegalArgumentException("symbol is null");
        String s = symbol.trim().toLowerCase(Locale.ROOT).replace("/", "");
        if (s.isEmpty()) throw new IllegalArgumentException("symbol is blank");
        return s;
    }

    private static String normTfLower(String timeframe) {
        if (timeframe == null) throw new IllegalArgumentException("timeframe is null");
        String s = timeframe.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) throw new IllegalArgumentException("timeframe is blank");
        return s;
    }
}
