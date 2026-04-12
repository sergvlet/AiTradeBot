package com.chicu.aitradebot.exchange.binance;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.exchange.parser.BinanceKlineParser;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.chicu.aitradebot.market.stream.MarketStreamRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceMarketStreamAdapter {

    // ✅ MAINNET
    private static final String WS_MAIN_STREAM_TEMPLATE =
            "wss://stream.binance.com:9443/stream?streams=%s";

    // ✅ TESTNET
    private static final String WS_TEST_STREAM_TEMPLATE =
            "wss://stream.testnet.binance.vision/stream?streams=%s";

    private final MarketStreamRouter router;
    private final BinanceKlineParser klineParser;

    // отдельный клиент на класс — ок под прод
    private final OkHttpClient client = new OkHttpClient();

    /**
     * key = BINANCE:NET:SYMBOL
     */
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();

    /**
     * key = BINANCE:NET:SYMBOL -> set(streams)
     */
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();

    // =====================================================
    // Legacy API (как было) — оставляем MAINNET
    // =====================================================

    public void connect(String symbol, List<String> streams) {
        connect(NetworkType.MAINNET, symbol, streams);
    }

    public void disconnect(String symbol) {
        disconnect(NetworkType.MAINNET, symbol);
    }

    public void subscribeTicker(String symbol) {
        subscribeTicker(NetworkType.MAINNET, symbol);
    }

    public void subscribeKline(String symbol, String interval) {
        subscribeKline(NetworkType.MAINNET, symbol, interval);
    }

    public void unsubscribeTicker(String symbol) {
        unsubscribeTicker(NetworkType.MAINNET, symbol);
    }

    public void unsubscribeKline(String symbol, String interval) {
        unsubscribeKline(NetworkType.MAINNET, symbol, interval);
    }

    // =====================================================
    // ✅ New overloads with NetworkType
    // =====================================================

    public void connect(NetworkType network, String symbol, List<String> streams) {
        NetworkType net = (network != null) ? network : NetworkType.MAINNET;

        String sym = normalizeSymbolOrThrow(symbol).toLowerCase(Locale.ROOT);
        String key = key(net, sym);

        Set<String> set = subscriptions.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        if (streams != null) set.addAll(streams);

        if (sockets.containsKey(key)) return;

        List<String> safeStreams = new ArrayList<>(set);
        if (safeStreams.isEmpty()) {
            safeStreams.add(sym + "@kline_1m");
        }

        String url = buildWsUrl(net, String.join("/", safeStreams));
        Request request = new Request.Builder().url(url).build();

        log.info("🔌 WS CONNECT BINANCE(legacy) net={} symbol={} url={}", net, sym.toUpperCase(Locale.ROOT), url);

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                log.info("✅ WS OPEN BINANCE(legacy) key={}", key);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleMessage(sym, text);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                log.warn("❌ WS CLOSED BINANCE(legacy) key={} code={} reason={}", key, code, reason);
                cleanup(key);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                String resp = (response != null) ? (response.code() + " " + response.message()) : "no-response";
                log.error("💥 WS FAIL BINANCE(legacy) key={} resp={} err={}", key, resp, t.toString());
                cleanup(key);
            }
        });

        sockets.put(key, ws);
    }

    public void disconnect(NetworkType network, String symbol) {
        NetworkType net = (network != null) ? network : NetworkType.MAINNET;

        String sym = normalizeSymbolOrThrow(symbol).toLowerCase(Locale.ROOT);
        String key = key(net, sym);

        WebSocket ws = sockets.remove(key);
        if (ws != null) {
            try { ws.close(1000, "disconnect"); } catch (Exception ignored) {}
        }
        subscriptions.remove(key);

        log.info("🔌 WS DISCONNECT BINANCE(legacy) key={}", key);
    }

    public void subscribeTicker(NetworkType network, String symbol) {
        NetworkType net = (network != null) ? network : NetworkType.MAINNET;

        String sym = normalizeSymbolOrThrow(symbol).toLowerCase(Locale.ROOT);
        String key = key(net, sym);

        subscriptions.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                .add(sym + "@trade");

        connect(net, sym, List.of(sym + "@trade"));
    }

    public void subscribeKline(NetworkType network, String symbol, String interval) {
        NetworkType net = (network != null) ? network : NetworkType.MAINNET;

        String sym = normalizeSymbolOrThrow(symbol).toLowerCase(Locale.ROOT);
        String tf = normalizeIntervalOrThrow(interval);

        String key = key(net, sym);

        subscriptions.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                .add(sym + "@kline_" + tf);

        connect(net, sym, List.of(sym + "@kline_" + tf));
    }

    public void unsubscribeTicker(NetworkType network, String symbol) {
        NetworkType net = (network != null) ? network : NetworkType.MAINNET;

        String sym = normalizeSymbolOrThrow(symbol).toLowerCase(Locale.ROOT);
        String key = key(net, sym);

        Set<String> set = subscriptions.get(key);
        if (set != null) set.remove(sym + "@trade");

        maybeDisconnectIfNoSubs(key, net, sym);
    }

    public void unsubscribeKline(NetworkType network, String symbol, String interval) {
        NetworkType net = (network != null) ? network : NetworkType.MAINNET;

        String sym = normalizeSymbolOrThrow(symbol).toLowerCase(Locale.ROOT);
        String tf = normalizeIntervalOrThrow(interval);

        String key = key(net, sym);

        Set<String> set = subscriptions.get(key);
        if (set != null) set.remove(sym + "@kline_" + tf);

        maybeDisconnectIfNoSubs(key, net, sym);
    }

    // =====================================================
    // INTERNAL
    // =====================================================

    private void maybeDisconnectIfNoSubs(String key, NetworkType network, String sym) {
        Set<String> set = subscriptions.get(key);
        boolean empty = (set == null || set.isEmpty());
        if (empty) disconnect(network, sym);
    }

    private void cleanup(String key) {
        sockets.remove(key);
        // subscriptions оставляем — intent подписок
    }

    private String buildWsUrl(NetworkType network, String streamsJoined) {
        String tpl = (network == NetworkType.TESTNET) ? WS_TEST_STREAM_TEMPLATE : WS_MAIN_STREAM_TEMPLATE;
        return String.format(tpl, streamsJoined);
    }

    private String key(NetworkType network, String symLower) {
        return "BINANCE:" + network + ":" + symLower;
    }

    private void handleMessage(String symLower, String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject data = root.has("data") ? root.getJSONObject("data") : root;

            String eventType = data.optString("e", "");

            // ✅ Тики игнорируем (router.routeTicker у тебя нет)
            if ("trade".equalsIgnoreCase(eventType)) {
                return;
            }

            if ("kline".equalsIgnoreCase(eventType)) {
                UnifiedKline kline = klineParser.parse(data);
                if (kline == null) return;

                router.routeKline(symLower.toUpperCase(Locale.ROOT), kline);
            }

        } catch (Exception e) {
            if (log.isDebugEnabled()) log.debug("WS legacy parse error: {}", e.toString());
        }
    }

    private static String normalizeSymbolOrThrow(String symbol) {
        if (symbol == null) throw new IllegalArgumentException("symbol is null");
        String s = symbol.trim().toUpperCase(Locale.ROOT).replace("/", "");
        if (s.isEmpty()) throw new IllegalArgumentException("symbol is blank");
        return s;
    }

    private static String normalizeIntervalOrThrow(String interval) {
        if (interval == null) throw new IllegalArgumentException("interval is null");
        String s = interval.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) throw new IllegalArgumentException("interval is blank");
        return s;
    }
}
