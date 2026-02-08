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
     * key = BINANCE:NET:chatId:TYPE:symbol[:tf]:CHANNEL
     * ⚠️ ВАЖНО:
     * - AGG_TRADE НЕ зависит от tf → ключ без tf
     * - KLINE зависит от tf → ключ с tf
     */
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();

    // =====================================================
    // PUBLIC API (используется MarketDataStreamService)
    // =====================================================

    public void subscribeAggTrade(NetworkType networkType,
                                  String symbol,
                                  String timeframeIgnored,
                                  long chatId,
                                  StrategyType strategyType) {

        NetworkType net = (networkType != null) ? networkType : NetworkType.MAINNET;

        String sym = normSymbolLowerSafe(symbol);
        if (sym == null || strategyType == null) return;

        // ✅ ключ БЕЗ tf
        String key = buildKeyAgg(chatId, strategyType, sym, net);
        if (sockets.containsKey(key)) return;

        String streams = sym + "@aggTrade";
        String wsUrl = buildWsUrl(net, streams);

        Request request = new Request.Builder().url(wsUrl).build();

        log.info("🔌 WS CONNECT BINANCE chatId={} type={} sym={} net={} channel=AGG_TRADE url={}",
                chatId, strategyType, sym.toUpperCase(Locale.ROOT), net, wsUrl);

        WebSocket ws = client.newWebSocket(request,
                new AggTradeListener(key, chatId, strategyType, sym, net));

        // защита от гонок: если параллельно уже положили — закрываем лишний
        WebSocket prev = sockets.putIfAbsent(key, ws);
        if (prev != null) {
            try { ws.close(1000, "duplicate"); } catch (Exception ignored) {}
        }
    }

    public void unsubscribeAggTrade(NetworkType networkType,
                                    String symbol,
                                    String timeframeIgnored,
                                    long chatId,
                                    StrategyType strategyType) {

        NetworkType net = (networkType != null) ? networkType : NetworkType.MAINNET;

        String sym = normSymbolLowerSafe(symbol);
        if (sym == null || strategyType == null) return;

        String key = buildKeyAgg(chatId, strategyType, sym, net);
        closeAndRemove(key, "unsubscribe");
    }

    public void subscribeKline(NetworkType networkType,
                               String symbol,
                               String timeframe,
                               long chatId,
                               StrategyType strategyType) {

        NetworkType net = (networkType != null) ? networkType : NetworkType.MAINNET;

        String sym = normSymbolLowerSafe(symbol);
        String tf  = normTfLowerSafe(timeframe);

        if (sym == null || tf == null || strategyType == null) return;

        String key = buildKeyKline(chatId, strategyType, sym, tf, net);
        if (sockets.containsKey(key)) return;

        String streams = sym + "@kline_" + tf;
        String wsUrl = buildWsUrl(net, streams);

        Request request = new Request.Builder().url(wsUrl).build();

        log.info("🔌 WS CONNECT BINANCE chatId={} type={} sym={} tf={} net={} channel=KLINE url={}",
                chatId, strategyType, sym.toUpperCase(Locale.ROOT), tf, net, wsUrl);

        WebSocket ws = client.newWebSocket(request,
                new KlineListener(key, chatId, strategyType, sym, tf, net));

        WebSocket prev = sockets.putIfAbsent(key, ws);
        if (prev != null) {
            try { ws.close(1000, "duplicate"); } catch (Exception ignored) {}
        }
    }

    public void unsubscribeKline(NetworkType networkType,
                                 String symbol,
                                 String timeframe,
                                 long chatId,
                                 StrategyType strategyType) {

        NetworkType net = (networkType != null) ? networkType : NetworkType.MAINNET;

        String sym = normSymbolLowerSafe(symbol);
        String tf  = normTfLowerSafe(timeframe);

        if (sym == null || tf == null || strategyType == null) return;

        String key = buildKeyKline(chatId, strategyType, sym, tf, net);
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
        private final NetworkType net;

        private AggTradeListener(String key,
                                 long chatId,
                                 StrategyType type,
                                 String symLower,
                                 NetworkType net) {
            this.key = key;
            this.chatId = chatId;
            this.type = type;
            this.symLower = symLower;
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
            removeIfSame(key, webSocket);
        }

        @Override
        public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, Response response) {
            String resp = (response != null) ? (response.code() + " " + response.message()) : "no-response";
            log.error("💥 WS FAIL BINANCE key={} resp={} err={}", key, resp, t.toString());
            removeIfSame(key, webSocket);
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

                // ✅ НОВАЯ сигнатура: БЕЗ timeframe
                marketStream.onAggTrade(
                        chatId,
                        type,
                        "BINANCE",
                        net,
                        symUpper,
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
            removeIfSame(key, webSocket);
        }

        @Override
        public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, Response response) {
            String resp = (response != null) ? (response.code() + " " + response.message()) : "no-response";
            log.error("💥 WS FAIL BINANCE key={} resp={} err={}", key, resp, t.toString());
            removeIfSame(key, webSocket);
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
        } catch (Exception ignored) {}

        log.info("🔌 WS DISCONNECT BINANCE key={} reason={}", key, reason);
    }

    /**
     * ✅ защита от гонок: удаляем сокет из map только если он тот же,
     * который закрылся/упал (иначе можно удалить уже новый сокет при рестарте).
     */
    private void removeIfSame(String key, WebSocket ws) {
        if (key == null || ws == null) return;
        sockets.compute(key, (k, cur) -> (cur == ws) ? null : cur);
    }

    private static String buildKeyAgg(long chatId,
                                      StrategyType strategyType,
                                      String symLower,
                                      NetworkType net) {
        return "BINANCE:" + net + ":" + chatId + ":" + strategyType.name() + ":" + symLower + ":AGG_TRADE";
    }

    private static String buildKeyKline(long chatId,
                                        StrategyType strategyType,
                                        String symLower,
                                        String tfLower,
                                        NetworkType net) {
        return "BINANCE:" + net + ":" + chatId + ":" + strategyType.name() + ":" + symLower + ":" + tfLower + ":KLINE";
    }

    private static String buildWsUrl(NetworkType networkType, String streams) {
        NetworkType nt = (networkType != null) ? networkType : NetworkType.MAINNET;
        String tpl = (nt == NetworkType.TESTNET) ? WS_TEST_STREAM_TEMPLATE : WS_MAIN_STREAM_TEMPLATE;
        return String.format(tpl, streams);
    }

    // ✅ безопасная нормализация без throw (чтобы рестарт не падал)
    private static String normSymbolLowerSafe(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toLowerCase(Locale.ROOT).replace("/", "");
        return s.isEmpty() ? null : s;
    }

    private static String normTfLowerSafe(String timeframe) {
        if (timeframe == null) return null;
        String s = timeframe.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }
}
