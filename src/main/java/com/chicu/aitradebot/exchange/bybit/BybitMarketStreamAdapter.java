package com.chicu.aitradebot.exchange.bybit;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.market.stream.MarketStreamRouter;
import com.chicu.aitradebot.market.stream.Tick;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class BybitMarketStreamAdapter {

    // Bybit V5 WS public spot:
    // mainnet: wss://stream.bybit.com/v5/public/spot
    // testnet: wss://stream-testnet.bybit.com/v5/public/spot
    private static final String WS_URL_MAINNET = "wss://stream.bybit.com/v5/public/spot";
    private static final String WS_URL_TESTNET = "wss://stream-testnet.bybit.com/v5/public/spot";

    private final MarketStreamRouter router;
    private final OkHttpClient client; // ✅ DI

    /**
     * ✅ Раздельные подключения по сети (MAINNET/TESTNET), чтобы не было каши.
     */
    private final Map<NetworkType, WebSocket> sockets = new ConcurrentHashMap<>();

    /**
     * ✅ Раздельные подписки по сети.
     */
    private final Map<NetworkType, Set<String>> subscribedTopicsByNet = new ConcurrentHashMap<>();

    // ============================================================
    // 🔌 CONNECT / DISCONNECT
    // ============================================================

    public synchronized void connect(NetworkType networkType) {
        NetworkType net = (networkType == null) ? NetworkType.MAINNET : networkType;

        if (sockets.get(net) != null) {
            log.info("🔁 Bybit WS уже подключён (net={})", net);
            return;
        }

        String url = wsUrl(net);
        Request req = new Request.Builder().url(url).build();

        WebSocket ws = client.newWebSocket(req, new BybitListener(net));
        sockets.put(net, ws);

        subscribedTopicsByNet.computeIfAbsent(net, __ -> ConcurrentHashMap.newKeySet());

        log.info("🔌 Bybit WS подключен (TICKER ONLY) net={} url={}", net, url);
    }

    public synchronized void disconnect(NetworkType networkType) {
        NetworkType net = (networkType == null) ? NetworkType.MAINNET : networkType;

        WebSocket ws = sockets.remove(net);
        if (ws != null) {
            try {
                ws.close(1000, "shutdown");
            } catch (Exception ignored) {}
        }

        Set<String> topics = subscribedTopicsByNet.remove(net);
        if (topics != null) topics.clear();

        log.info("🔌 Bybit WS отключен (net={})", net);
    }

    public boolean isConnected(NetworkType networkType) {
        NetworkType net = (networkType == null) ? NetworkType.MAINNET : networkType;
        return sockets.get(net) != null;
    }

    // ============================================================
    // ✅ BACKWARD COMPAT (старые методы без сети)
    // ============================================================

    public synchronized void connect() {
        connect(NetworkType.MAINNET);
    }

    public synchronized void disconnect() {
        disconnect(NetworkType.MAINNET);
    }

    public boolean isConnected() {
        return isConnected(NetworkType.MAINNET);
    }

    // ============================================================
    // 📡 SUBSCRIBE / UNSUBSCRIBE (TICKER ONLY)
    // ============================================================

    /**
     * ✅ Новый метод (как у тебя в StreamConnectionManager): с networkType
     */
    public synchronized void subscribeTicker(NetworkType networkType, String symbol) {
        NetworkType net = (networkType == null) ? NetworkType.MAINNET : networkType;

        String sym = normalize(symbol);
        if (sym.isEmpty()) return;

        if (sockets.get(net) == null) connect(net);

        String topic = "tickers." + sym;

        Set<String> topics = subscribedTopicsByNet.computeIfAbsent(net, __ -> ConcurrentHashMap.newKeySet());
        if (!topics.add(topic)) return;

        send(net, "subscribe", topic);
    }

    /**
     * ✅ Новый метод (как у тебя в StreamConnectionManager): с networkType
     */
    public synchronized void unsubscribeTicker(NetworkType networkType, String symbol) {
        NetworkType net = (networkType == null) ? NetworkType.MAINNET : networkType;

        String sym = normalize(symbol);
        if (sym.isEmpty()) return;

        String topic = "tickers." + sym;

        Set<String> topics = subscribedTopicsByNet.computeIfAbsent(net, __ -> ConcurrentHashMap.newKeySet());
        if (!topics.remove(topic)) return;

        send(net, "unsubscribe", topic);
    }

    /**
     * Старые методы оставляем — делегируем в MAINNET
     */
    public synchronized void subscribeTicker(String symbol) {
        subscribeTicker(NetworkType.MAINNET, symbol);
    }

    public synchronized void unsubscribeTicker(String symbol) {
        unsubscribeTicker(NetworkType.MAINNET, symbol);
    }

    // ============================================================
    // 📨 SEND
    // ============================================================

    private void send(NetworkType net, String op, String topic) {
        WebSocket ws = sockets.get(net);
        if (ws == null) {
            log.warn("⚠️ Bybit WS send skipped — ws == null (net={}), topic={}", net, topic);
            return;
        }

        JSONObject req = new JSONObject()
                .put("op", op)
                .put("args", new JSONArray().put(topic));

        ws.send(req.toString());
        log.info("📡 [BYBIT] {} {} (net={})", op.toUpperCase(), topic, net);
    }

    // ============================================================
    // 🧠 LISTENER
    // ============================================================

    private class BybitListener extends WebSocketListener {

        private final NetworkType net;

        private BybitListener(NetworkType net) {
            this.net = (net == null) ? NetworkType.MAINNET : net;
        }

        @Override
        public void onOpen(WebSocket ws, Response response) {
            log.info("✅ Bybit WS onOpen (net={})", net);
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            try {
                JSONObject obj = new JSONObject(text);

                // ping/pong/ack
                if ("pong".equalsIgnoreCase(obj.optString("op"))) return;
                if (obj.optBoolean("success")) return;

                String topic = obj.optString("topic", "");
                if (topic.startsWith("tickers.")) {
                    parseTicker(obj);
                }

            } catch (Exception e) {
                log.error("❌ Bybit WS parse error (net={}): {}", net, e.getMessage(), e);
            }
        }

        @Override
        public void onMessage(WebSocket ws, ByteString bytes) {
            onMessage(ws, bytes.utf8());
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            log.error("❌ Bybit WS failure (net={}): {}", net, t.getMessage(), t);

            // ✅ сбрасываем сокет для этой сети (и подписки), чтобы следующий subscribe сделал reconnect
            sockets.remove(net);

            Set<String> topics = subscribedTopicsByNet.remove(net);
            if (topics != null) topics.clear();
        }
    }

    // ============================================================
    // 📌 TICKER → MarketStreamRouter
    // ============================================================

    private void parseTicker(JSONObject obj) {
        Object node = obj.opt("data");
        if (node == null) return;

        JSONObject data = (node instanceof JSONArray arr && !arr.isEmpty())
                ? arr.getJSONObject(0)
                : (node instanceof JSONObject o ? o : null);

        if (data == null) return;

        String symbol = data.optString("symbol", "");
        if (symbol.isEmpty()) return;

        String priceStr = data.optString("lastPrice",
                data.optString("bid1Price", null));
        if (priceStr == null) return;

        try {
            BigDecimal price = new BigDecimal(priceStr);
            long ts = obj.optLong("ts", System.currentTimeMillis());

            // ⚠️ В Tick у тебя сейчас нет networkType/chatId.
            // Если будешь одновременно держать MAINNET и TESTNET по BYBIT — лучше расширить Tick.
            router.route(new Tick(
                    "BYBIT",
                    symbol,
                    price,
                    ts
            ));

        } catch (Exception e) {
            log.debug("⚠️ Bybit bad price '{}'", priceStr);
        }
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private static String wsUrl(NetworkType net) {
        return net == NetworkType.TESTNET ? WS_URL_TESTNET : WS_URL_MAINNET;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replace("/", "").trim().toUpperCase();
    }
}
