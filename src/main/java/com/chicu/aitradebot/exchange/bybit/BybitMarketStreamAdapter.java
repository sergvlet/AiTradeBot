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

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class BybitMarketStreamAdapter {

    private static final String WS_URL = "wss://stream.bybit.com/v5/public/spot";

    private final MarketStreamRouter router;

    private final OkHttpClient client = new OkHttpClient();

    private WebSocket webSocket;

    /** ⭐ Трек текущих подписок */
    private final Set<String> subscribedSymbols = new HashSet<>();

    // ============================================================
    // 🔌 CONNECT / DISCONNECT / STATE
    // ============================================================

    public synchronized void connect() {
        if (webSocket != null) {
            log.info("🔁 Bybit WS уже подключён");
            return;
        }

        Request req = new Request.Builder().url(WS_URL).build();

        webSocket = client.newWebSocket(req, new BybitListener());
        log.info("🔌 Bybit WS connected");
    }

    public synchronized void disconnect() {
        if (webSocket != null) {
            webSocket.close(1000, "shutdown");
            webSocket = null;
            log.info("🔌 Bybit WS disconnected");
        }
    }

    /** ⭐ Нужно для StreamConnectionManager */
    public boolean isConnected() {
        return webSocket != null;
    }

    // ============================================================
    // 📡 SUBSCRIBE
    // ============================================================

    public synchronized void subscribeTicker(String symbol) {
        symbol = normalize(symbol);

        if (webSocket == null) {
            connect();
        }

        if (!subscribedSymbols.add(symbol)) {
            return; // уже есть подписка
        }

        sendSubscribe(symbol);
    }

    // ============================================================
    // 🔕 UNSUBSCRIBE
    // ============================================================

    public synchronized void unsubscribeTicker(String symbol) {
        symbol = normalize(symbol);

        if (!subscribedSymbols.remove(symbol)) {
            return; // нечего отписывать
        }

        sendUnsubscribe(symbol);
    }

    public synchronized void unsubscribeAll() {
        for (String s : subscribedSymbols) {
            sendUnsubscribe(s);
        }
        subscribedSymbols.clear();
    }

    // ============================================================
    // 📡 SEND subscribe/unsubscribe
    // ============================================================

    private void sendSubscribe(String symbol) {
        if (webSocket == null) {
            log.warn("⚠️ Bybit WS sendSubscribe: webSocket == null");
            return;
        }

        String topic = "tickers." + symbol;

        JSONObject req = new JSONObject()
                .put("op", "subscribe")
                .put("args", new JSONArray().put(topic));

        webSocket.send(req.toString());

        log.info("📡 [BYBIT] SUBSCRIBE {}", topic);
    }

    private void sendUnsubscribe(String symbol) {
        if (webSocket == null) {
            log.warn("⚠️ Bybit WS sendUnsubscribe: webSocket == null");
            return;
        }

        String topic = "tickers." + symbol;

        JSONObject req = new JSONObject()
                .put("op", "unsubscribe")
                .put("args", new JSONArray().put(topic));

        webSocket.send(req.toString());
        log.info("🔕 [BYBIT] UNSUBSCRIBE {}", topic);
    }

    // ============================================================
    // 🧠 LISTENER
    // ============================================================

    private class BybitListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            log.info("✅ Bybit WS onOpen: {}", response.message());
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                handleMessage(text);
            } catch (Exception e) {
                log.error("❌ Bybit WS parse error: {}", e.getMessage(), e);
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            onMessage(webSocket, bytes.utf8());
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            log.warn("⚠️ Bybit WS closing: {} / {}", code, reason);
            webSocket.close(1000, null);
            BybitMarketStreamAdapter.this.webSocket = null;
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            log.error("❌ Bybit WS failure: {}", t.getMessage(), t);
            BybitMarketStreamAdapter.this.webSocket = null;

            // ВАЖНО — не трогаем subscribedSymbols, стратегии пересоздадут подписки
        }
    }

    // ============================================================
    // 🔍 MESSAGE PARSER
    // ============================================================

    private void handleMessage(String raw) throws IOException {
        JSONObject obj = new JSONObject(raw);

        if ("pong".equalsIgnoreCase(obj.optString("op"))) return;
        if (obj.optBoolean("success")) return;

        String topic = obj.optString("topic", "");
        if (!topic.startsWith("tickers.")) return;

        if (!obj.has("data")) return;

        JSONObject data;

        Object node = obj.get("data");
        if (node instanceof JSONArray arr) {
            if (arr.isEmpty()) return;
            data = arr.getJSONObject(0);
        } else {
            data = (JSONObject) node;
        }

        String symbol = data.optString("symbol", null);
        if (symbol == null || symbol.isEmpty()) {
            if (topic.contains(".")) {
                symbol = topic.substring(topic.indexOf('.') + 1);
            } else return;
        }

        String lastPriceStr = data.optString("lastPrice", null);
        if (lastPriceStr == null || lastPriceStr.isEmpty())
            lastPriceStr = data.optString("bid1Price", null);

        if (lastPriceStr == null || lastPriceStr.isEmpty()) return;

        BigDecimal price;
        try {
            price = new BigDecimal(lastPriceStr);
        } catch (Exception e) {
            return;
        }

        long ts = obj.optLong("ts", System.currentTimeMillis());

        router.route(new Tick("BYBIT", symbol, price, ts));
    }

    // ============================================================
    // 🔧 HELPERS
    // ============================================================

    private String normalize(String s) {
        if (s == null) return "";
        return s.replace("/", "").trim().toUpperCase();
    }
}
