package com.chicu.aitradebot.exchange.binance;

import com.chicu.aitradebot.market.stream.MarketStreamRouter;
import com.chicu.aitradebot.market.stream.Tick;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceMarketStreamAdapter {

    private final MarketStreamRouter router;

    private final OkHttpClient client = new OkHttpClient();
    private WebSocket ws;

    // ⭐ уникальные ID сообщений
    private final AtomicInteger msgId = new AtomicInteger(1);

    // ============================================================
    //   CONNECT / DISCONNECT / STATE
    // ============================================================

    public void connect() {
        Request req = new Request.Builder()
                .url("wss://stream.binance.com:9443/ws")
                .build();

        ws = client.newWebSocket(req, new BinanceListener());
        log.info("🔌 Binance WS connected");
    }

    public void disconnect() {
        if (ws != null) {
            ws.close(1000, "shutdown");
            ws = null;
            log.info("🔌 Binance WS disconnected");
        }
    }

    public boolean isConnected() {
        return ws != null;
    }

    // ============================================================
    //   SUBSCRIBE
    // ============================================================

    public void subscribeTicker(String symbol) {
        String s = normalize(symbol);
        send("{\"method\":\"SUBSCRIBE\",\"params\":[\"" + s + "@ticker\"],\"id\":" + msgId.getAndIncrement() + "}");
        log.info("📡 Binance SUBSCRIBE ticker {}", s);
    }

    public void subscribeKline(String symbol, String timeframe) {
        String s = normalize(symbol);
        send("{\"method\":\"SUBSCRIBE\",\"params\":[\"" + s + "@kline_" + timeframe + "\"],\"id\":" + msgId.getAndIncrement() + "}");
        log.info("📡 Binance SUBSCRIBE kline {} {}", s, timeframe);
    }

    public void subscribeTrades(String symbol) {
        String s = normalize(symbol);
        send("{\"method\":\"SUBSCRIBE\",\"params\":[\"" + s + "@trade\"],\"id\":" + msgId.getAndIncrement() + "}");
        log.info("📡 Binance SUBSCRIBE trades {}", s);
    }

    // ============================================================
    //   UNSUBSCRIBE
    // ============================================================

    public void unsubscribeTicker(String symbol) {
        String s = normalize(symbol);
        send("{\"method\":\"UNSUBSCRIBE\",\"params\":[\"" + s + "@ticker\"],\"id\":" + msgId.getAndIncrement() + "}");
        log.info("🔌 Binance UNSUBSCRIBE ticker {}", s);
    }

    public void unsubscribeKline(String symbol, String timeframe) {
        String s = normalize(symbol);
        send("{\"method\":\"UNSUBSCRIBE\",\"params\":[\"" + s + "@kline_" + timeframe + "\"],\"id\":" + msgId.getAndIncrement() + "}");
        log.info("🔌 Binance UNSUBSCRIBE kline {} {}", s, timeframe);
    }

    public void unsubscribeTrades(String symbol) {
        String s = normalize(symbol);
        send("{\"method\":\"UNSUBSCRIBE\",\"params\":[\"" + s + "@trade\"],\"id\":" + msgId.getAndIncrement() + "}");
        log.info("🔌 Binance UNSUBSCRIBE trades {}", s);
    }

    // ============================================================
    //   INTERNAL
    // ============================================================

    private void send(String msg) {
        try {
            if (ws != null) {
                ws.send(msg);
            } else {
                log.warn("⚠️ Binance WS send skipped — ws == null, msg={}", msg);
            }
        } catch (Exception e) {
            log.error("❌ Binance WS send error: {}", e.getMessage(), e);
        }
    }

    private String normalize(String symbol) {
        if (symbol == null) return "";
        return symbol.trim().replace("/", "").toLowerCase();
    }

    public String exchange() {
        return "BINANCE";
    }

    private class BinanceListener extends WebSocketListener {

        @Override
        public void onMessage(WebSocket webSocket, String msg) {
            try {
                JSONObject json = new JSONObject(msg);

                // Binance ticker stream
                if (json.has("c") && json.has("s")) {

                    String symbol = json.getString("s");
                    BigDecimal price = new BigDecimal(json.getString("c"));

                    router.route(new Tick(
                            "BINANCE",
                            symbol,
                            price,
                            System.currentTimeMillis()
                    ));
                }

            } catch (Exception ex) {
                log.error("❌ Binance WS parse error: {}", ex.getMessage(), ex);
            }
        }
    }
}
