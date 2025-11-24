package com.chicu.aitradebot.exchange.binance;

import com.chicu.aitradebot.market.MarketTickListener;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class BinanceWebSocketClient {

    private static final String BASE_SPOT = "wss://stream.binance.com:9443/ws/";

    private final OkHttpClient client;

    public BinanceWebSocketClient() {
        this.client = new OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public void connect(String symbol, MarketTickListener listener) {

        String pair = symbol.toLowerCase() + "@trade";
        Request req = new Request.Builder()
                .url(BASE_SPOT + pair)
                .build();

        client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response resp) {
                log.info("🌐 Binance WS OPEN {}", pair);
            }

            @Override
            public void onMessage(@NotNull WebSocket ws, @NotNull String json) {
                try {
                    var obj = new org.json.JSONObject(json);
                    double price = obj.getDouble("p");
                    double qty   = obj.getDouble("q");
                    long ts      = obj.getLong("T");

                    listener.onTick(symbol, qty, ts, price);

                } catch (Exception ex) {
                    log.error("WS parse error: {}", ex.getMessage());
                }
            }

            @Override
            public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, Response r) {
                log.error("❌ WS FAIL {}: {}", pair, t.getMessage());
            }
        });
    }
}
