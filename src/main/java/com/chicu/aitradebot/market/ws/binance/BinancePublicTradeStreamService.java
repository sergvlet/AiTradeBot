package com.chicu.aitradebot.market.ws.binance;

import com.chicu.aitradebot.market.ws.TradeFeedListener;
import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionCandleService;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BinancePublicTradeStreamService {

    private static final String BINANCE_WS_URL = "wss://stream.binance.com:9443/ws/";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** symbol → WebSocket */
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();

    /** Основной слушатель трейдов */
    @Setter
    private volatile TradeFeedListener listener;

    /** Используем для live-свечей SmartFusion по умолчанию */
    private final SmartFusionCandleService candleService;

    /** Авто-реконнект */
    private final ScheduledExecutorService reconnectExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "binance-reconnect");
                t.setDaemon(true);
                return t;
            });

    @PostConstruct
    public void init() {
        // SmartFusionCandleService → дефолтный получатель трейдов,
        // но MarketStreamManager заменит его через setListener()
        this.listener = candleService;
        log.info("✅ Binance WS listener = SmartFusionCandleService (default)");
    }

    // ===============================================================
    // SUBSCRIBE
    // ===============================================================
    public void subscribeSymbol(String symbol) {
        symbol = symbol.toUpperCase(Locale.ROOT);
        openSocketIfNeeded(symbol);
    }

    public void subscribeSymbols(Iterable<String> symbols) {
        for (String s : symbols) subscribeSymbol(s);
    }

    // ===============================================================
    // UNSUBSCRIBE
    // ===============================================================
    public void unsubscribe(String symbol) {
        symbol = symbol.toUpperCase(Locale.ROOT);

        WebSocket ws = sockets.remove(symbol);
        if (ws != null) {
            try {
                log.info("🔻 Binance WS закрываем поток {}", symbol);
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "client unsubscribe");
            } catch (Exception e) {
                log.error("❌ WS close error {}: {}", symbol, e.getMessage());
            }
        }
    }

    // ===============================================================
    // INTERNAL
    // ===============================================================
    private void openSocketIfNeeded(String symbol) {
        if (sockets.containsKey(symbol)) return;

        String url = BINANCE_WS_URL + symbol.toLowerCase() + "@trade";

        log.info("⚡ Binance WS подключение: {}", url);

        httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(url), new WebSocket.Listener() {

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        log.info("✅ WS открыт для {}", symbol);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        handleMessage(symbol, data.toString());
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        log.error("❌ WS ошибка {}: {}", symbol, error.getMessage());
                        sockets.remove(symbol);
                        scheduleReconnect(symbol);
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
                        log.warn("⏹ WS закрыт {} ({})", symbol, reason);
                        sockets.remove(symbol);
                        scheduleReconnect(symbol);
                        return CompletableFuture.completedFuture(null);
                    }
                })
                .whenComplete((ws, ex) -> {
                    if (ex != null) {
                        log.error("❌ Ошибка открытия WS {}: {}", symbol, ex.getMessage());
                        scheduleReconnect(symbol);
                    } else {
                        sockets.put(symbol, ws);
                    }
                });
    }

    private void handleMessage(String symbolKey, String jsonString) {
        try {
            JSONObject json = new JSONObject(jsonString);

            String symbol = json.optString("s", symbolKey);
            double price = json.optDouble("p");
            long ts = json.optLong("T", json.optLong("E", System.currentTimeMillis()));

            TradeFeedListener l = listener;
            if (l != null) {
                l.onTrade(symbol, BigDecimal.valueOf(price), ts);
            }

        } catch (Exception e) {
            log.warn("⚠️ Ошибка разбора trade {}: {}", symbolKey, e.getMessage());
        }
    }

    private void scheduleReconnect(String symbol) {
        reconnectExecutor.schedule(() -> {
            log.info("🔁 Binance WS Reconnect {}", symbol);
            openSocketIfNeeded(symbol);
        }, 3, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        sockets.values().forEach(ws -> {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"); }
            catch (Exception ignored) {}
        });
        reconnectExecutor.shutdownNow();
    }
}
