package com.chicu.aitradebot.exchange.binance;

import com.chicu.aitradebot.market.MarketStreamService;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.chicu.aitradebot.market.stream.MarketStreamRouter;
import com.chicu.aitradebot.market.stream.Tick;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceMarketStreamAdapter {

    private final MarketStreamRouter router;
    private final MarketStreamService marketStreamService;

    private final OkHttpClient client = new OkHttpClient();
    private WebSocket ws;

    private final AtomicInteger msgId = new AtomicInteger(1);

    // ============================================================
    // 🔌 CONNECT / DISCONNECT / STATE
    // ============================================================

    public synchronized void connect() {
        if (ws != null) {
            log.info("🔁 Binance WS уже подключён");
            return;
        }

        Request req = new Request.Builder()
                .url("wss://stream.binance.com:9443/ws")
                .build();

        ws = client.newWebSocket(req, new BinanceListener());
        log.info("🔌 Binance WS connected");
    }

    public synchronized void disconnect() {
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
    // 📡 SUBSCRIBE
    // ============================================================

    public synchronized void subscribeTicker(String symbol) {
        String s = normalize(symbol);
        send("""
             {"method":"SUBSCRIBE","params":["%s@ticker"],"id":%d}
             """.formatted(s, msgId.getAndIncrement()));
    }

    public synchronized void subscribeKline(String symbol, String timeframe) {
        String s = normalize(symbol);
        send("""
             {"method":"SUBSCRIBE","params":["%s@kline_%s"],"id":%d}
             """.formatted(s, timeframe, msgId.getAndIncrement()));
    }

    // ============================================================
    // 🔕 UNSUBSCRIBE
    // ============================================================

    public synchronized void unsubscribeTicker(String symbol) {
        String s = normalize(symbol);

        if (ws == null) {
            log.warn("⚠ Binance WS unsubscribeTicker skipped — ws == null");
            return;
        }

        String msg = """
            {"method":"UNSUBSCRIBE","params":["%s@ticker"],"id":%d}
            """.formatted(s, msgId.getAndIncrement());

        ws.send(msg);

        log.info("🔌 Binance UNSUBSCRIBE ticker {}", s);
    }

    // ============================================================
    // 📨 SEND
    // ============================================================

    private void send(String msg) {
        if (ws == null) {
            log.warn("⚠ Binance WS send skipped — ws == null");
            return;
        }
        ws.send(msg);
    }

    private String normalize(String symbol) {
        return symbol.replace("/", "").trim().toLowerCase();
    }

    private String exchange() {
        return "BINANCE";
    }

    // ============================================================
    // 🧠 LISTENER
    // ============================================================

    private class BinanceListener extends WebSocketListener {

        @Override
        public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
            log.info("🟢 Binance WS onOpen");
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull String msg) {
            try {
                JSONObject json = new JSONObject(msg);

                // TICKER
                if (json.has("s") && json.has("c")) {
                    router.route(new Tick(
                            exchange(),
                            json.getString("s"),
                            new BigDecimal(json.getString("c")),
                            System.currentTimeMillis()
                    ));
                    return;
                }

                // KLINE
                if ("kline".equals(json.optString("e"))) {

                    JSONObject k = json.getJSONObject("k");

                    UnifiedKline uk = UnifiedKline.builder()
                            .symbol(k.getString("s"))
                            .timeframe(k.getString("i"))
                            .openTime(k.getLong("t"))
                            .open(new BigDecimal(k.getString("o")))
                            .high(new BigDecimal(k.getString("h")))
                            .low(new BigDecimal(k.getString("l")))
                            .close(new BigDecimal(k.getString("c")))
                            .volume(new BigDecimal(k.getString("v")))
                            .build();

                    marketStreamService.onKline(uk);
                }

            } catch (Exception e) {
                log.error("❌ Binance WS parse error", e);
            }
        }

        @Override
        public void onFailure(@NotNull WebSocket webSocket,
                              @NotNull Throwable t,
                              Response response) {
            log.error("❌ Binance WS failure", t);
            ws = null;
        }
    }
}
