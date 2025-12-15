package com.chicu.aitradebot.exchange.bybit;

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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class BybitMarketStreamAdapter {

    private static final String WS_URL = "wss://stream.bybit.com/v5/public/spot";

    private final MarketStreamRouter router;
    private final OkHttpClient client; // ✅ DI, БЕЗ new

    private WebSocket webSocket;

    /** ✔ thread-safe set */
    private final Set<String> subscribedTopics =
            ConcurrentHashMap.newKeySet();

    // ============================================================
    // 🔌 CONNECT / DISCONNECT
    // ============================================================

    public synchronized void connect() {
        if (webSocket != null) {
            log.info("🔁 Bybit WS уже подключён");
            return;
        }

        Request req = new Request.Builder()
                .url(WS_URL)
                .build();

        webSocket = client.newWebSocket(req, new BybitListener());
        log.info("🔌 Bybit WS подключен (TICKER ONLY)");
    }

    public synchronized void disconnect() {
        if (webSocket != null) {
            webSocket.close(1000, "shutdown");
            webSocket = null;
            subscribedTopics.clear();
            log.info("🔌 Bybit WS отключен");
        }
    }

    public boolean isConnected() {
        return webSocket != null;
    }

    // ============================================================
    // 📡 SUBSCRIBE / UNSUBSCRIBE (TICKER ONLY)
    // ============================================================

    public synchronized void subscribeTicker(String symbol) {
        String sym = normalize(symbol);
        if (sym.isEmpty()) return;

        if (webSocket == null) connect();

        String topic = "tickers." + sym;
        if (!subscribedTopics.add(topic)) return;

        send("subscribe", topic);
    }

    public synchronized void unsubscribeTicker(String symbol) {
        String sym = normalize(symbol);
        if (sym.isEmpty()) return;

        String topic = "tickers." + sym;
        if (!subscribedTopics.remove(topic)) return;

        send("unsubscribe", topic);
    }

    // ============================================================
    // 📨 SEND
    // ============================================================

    private void send(String op, String topic) {
        if (webSocket == null) {
            log.warn("⚠️ Bybit WS send skipped — ws == null, topic={}", topic);
            return;
        }

        JSONObject req = new JSONObject()
                .put("op", op)
                .put("args", new JSONArray().put(topic));

        webSocket.send(req.toString());
        log.info("📡 [BYBIT] {} {}", op.toUpperCase(), topic);
    }

    // ============================================================
    // 🧠 LISTENER
    // ============================================================

    private class BybitListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket ws, Response response) {
            log.info("✅ Bybit WS onOpen");
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            try {
                JSONObject obj = new JSONObject(text);

                if ("pong".equalsIgnoreCase(obj.optString("op"))) return;
                if (obj.optBoolean("success")) return;

                String topic = obj.optString("topic", "");
                if (topic.startsWith("tickers.")) {
                    parseTicker(obj);
                }

            } catch (Exception e) {
                log.error("❌ Bybit WS parse error: {}", e.getMessage(), e);
            }
        }

        @Override
        public void onMessage(WebSocket ws, ByteString bytes) {
            onMessage(ws, bytes.utf8());
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            log.error("❌ Bybit WS failure: {}", t.getMessage(), t);
            webSocket = null;
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

    private String normalize(String s) {
        return s == null ? "" : s.replace("/", "").trim().toUpperCase();
    }
}
