package com.chicu.aitradebot.market.ws.bybit;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.market.ws.TradeFeedListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BybitPublicTradeStreamService {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** symbol → websocket */
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();

    private volatile TradeFeedListener listener;

    /** Реконнект */
    private final ScheduledExecutorService reconnectExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "bybit-reconnect");
                t.setDaemon(true);
                return t;
            });

    public void setListener(TradeFeedListener listener) {
        this.listener = listener;
    }

    private String getWsUrl(NetworkType network) {
        return switch (network) {
            case MAINNET -> "wss://stream.bybit.com/v5/public/spot";
            case TESTNET -> "wss://stream-testnet.bybit.com/v5/public/spot";
        };
    }

    /** Подписка на 1 символ */
    public void subscribe(String symbol, NetworkType network) {
        String key = symbol.toUpperCase();

        if (sockets.containsKey(key)) return;

        String url = getWsUrl(network);

        log.info("⚡ Bybit WS connecting: {} → {}", key, url);

        httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(url), new BybitListener(key))
                .whenComplete((ws, ex) -> {
                    if (ex != null) {
                        log.error("❌ Bybit WS error {}: {}", key, ex.getMessage());
                        scheduleReconnect(key, network);
                    } else {
                        sockets.put(key, ws);
                        sendSubscribeMessage(ws, key);
                    }
                });
    }

    public void unsubscribe(String symbol) {
        String key = symbol.toUpperCase();

        WebSocket ws = sockets.remove(key);
        if (ws == null) {
            log.warn("⚠️ Bybit WS: нет активного подключения для {} (unsubscribe)", key);
            return;
        }

        try {
            log.info("🔻 Bybit WS: закрываем поток для {}", key);
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "client unsubscribe");
        } catch (Exception e) {
            log.error("❌ Bybit WS: ошибка при закрытии {}: {}", key, e.getMessage());
        }
    }

    /** Посылаем команду SUBSCRIBE после подключения */
    private void sendSubscribeMessage(WebSocket ws, String symbol) {
        String msg = new JSONObject()
                .put("op", "subscribe")
                .put("args", new JSONArray().put("publicTrade." + symbol))
                .toString();

        ws.sendText(msg, true);
        log.info("📡 → Bybit subscribed: {}", msg);
    }

    // =====================================================================
    //  WebSocket Listener
    // =====================================================================

    private class BybitListener implements WebSocket.Listener {
        private final String symbol; // символ, с которым мы подписывались

        public BybitListener(String symbol) {
            this.symbol = symbol;
        }

        @Override
        public void onOpen(WebSocket ws) {
            log.info("✅ Bybit WS open for {}", symbol);
            ws.request(1); // просим первое сообщение
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            String json = data.toString();

            try {
                JSONObject obj = new JSONObject(json);

                // Попробуем вытащить символ из data[0].s или topic
                String effectiveSymbol = this.symbol;

                JSONArray arr = obj.optJSONArray("data");
                if (arr != null && !arr.isEmpty()) {
                    JSONObject trade = arr.getJSONObject(0);
                    String s = trade.optString("s", null);
                    if (s != null && !s.isBlank()) {
                        effectiveSymbol = s;
                    }
                } else {
                    // fallback: попробуем из topic
                    String topic = obj.optString("topic", null);
                    if (topic != null && topic.startsWith("publicTrade.")) {
                        effectiveSymbol = topic.substring("publicTrade.".length());
                    }
                }

                handleMessage(effectiveSymbol, json);

            } catch (Exception e) {
                log.error("❌ Bybit WS parse error for {}: {}", symbol, e.getMessage());
            }

            ws.request(1); // просим следующее сообщение
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            log.warn("⛔ Bybit WS closed {}: {} ({})", symbol, status, reason);
            sockets.remove(symbol);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.error("❌ Bybit WS error {}: {}", symbol, error.getMessage());
            sockets.remove(symbol);
        }
    }

    // =====================================================================
    //  Обработка JSON и пуш в TradeFeedListener
    // =====================================================================

    /** Парсим события publicTrade и пушим в TradeFeedListener */
    private void handleMessage(String symbolKey, String jsonString) {
        try {
            JSONObject json = new JSONObject(jsonString);

            JSONArray dataArray = json.optJSONArray("data");
            if (dataArray == null || dataArray.isEmpty()) {
                return;
            }

            TradeFeedListener l = listener;
            if (l == null) {
                return;
            }

            for (int i = 0; i < dataArray.length(); i++) {
                JSONObject trade = dataArray.getJSONObject(i);

                String symbol = trade.optString("s", symbolKey);
                double price = trade.optDouble("p");
                double qty = trade.optDouble("q");
                long ts = trade.optLong("T", json.optLong("ts", System.currentTimeMillis()));

                l.onTrade(
                        symbol,
                        BigDecimal.valueOf(price),
                        ts
                );
            }

        } catch (Exception e) {
            log.warn("Ошибка разбора trade {}: {}", symbolKey, e.getMessage());
        }
    }

    private void scheduleReconnect(String symbol, NetworkType network) {
        reconnectExecutor.schedule(() -> {
            log.info("🔁 Bybit reconnect {}", symbol);
            subscribe(symbol, network);
        }, 3, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        sockets.values().forEach(ws -> {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            } catch (Exception ignored) {
            }
        });
        reconnectExecutor.shutdownNow();
    }
}
