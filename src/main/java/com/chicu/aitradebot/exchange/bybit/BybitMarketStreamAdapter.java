package com.chicu.aitradebot.exchange.bybit;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.parser.BybitKlineParser;
import com.chicu.aitradebot.market.MarketStreamService;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.chicu.aitradebot.market.stream.MarketStreamRouter;
import com.chicu.aitradebot.market.stream.Tick;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Component
public class BybitMarketStreamAdapter {

    // Bybit V5 WS public spot
    private static final String WS_URL_MAINNET = "wss://stream.bybit.com/v5/public/spot";
    private static final String WS_URL_TESTNET = "wss://stream-testnet.bybit.com/v5/public/spot";

    private final MarketStreamRouter router;
    private final MarketStreamService marketStream;
    private final OkHttpClient client;
    private final BybitKlineParser klineParser = new BybitKlineParser();

    @Value("${exchange.bybit.ws.reconnect.enabled:true}")
    private boolean reconnectEnabled;

    @Value("${exchange.bybit.ws.reconnect.delayMs:1500}")
    private long reconnectDelayMs;

    /**
     * Раздельные подключения по сети.
     */
    private final Map<NetworkType, WebSocket> sockets = new ConcurrentHashMap<>();

    /**
     * Legacy raw topics (старый UI/stream manager).
     */
    private final Map<NetworkType, Set<String>> rawTopicsByNet = new ConcurrentHashMap<>();

    /**
     * Topic -> subscribers (для strategy-aware доставки).
     */
    private final Map<NetworkType, Map<String, Set<SubscriptionSpec>>> specsByTopicByNet = new ConcurrentHashMap<>();

    /**
     * Topic ref-count (raw + strategy subscriptions).
     */
    private final Map<NetworkType, Map<String, Integer>> topicRefCountByNet = new ConcurrentHashMap<>();

    /**
     * key -> last message wall-clock time.
     */
    private final Map<String, Long> lastMessageAt = new ConcurrentHashMap<>();

    /**
     * reconnect tasks per network.
     */
    private final Map<NetworkType, ScheduledFuture<?>> reconnectTasks = new ConcurrentHashMap<>();

    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bybit-ws-reconnect");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean shuttingDown = false;

    public BybitMarketStreamAdapter(MarketStreamRouter router,
                                    MarketStreamService marketStream,
                                    OkHttpClient client) {
        this.router = router;
        this.marketStream = marketStream;
        this.client = client;
    }

    @PreDestroy
    public void shutdown() {
        shuttingDown = true;

        for (ScheduledFuture<?> f : reconnectTasks.values()) {
            try { f.cancel(false); } catch (Exception ignored) {}
        }
        reconnectTasks.clear();

        for (WebSocket ws : sockets.values()) {
            try { ws.close(1001, "shutdown"); } catch (Exception ignored) {
                try { ws.cancel(); } catch (Exception ignored2) {}
            }
        }
        sockets.clear();

        try { reconnectExecutor.shutdownNow(); } catch (Exception ignored) {}
    }

    // ============================================================
    // CONNECT / DISCONNECT
    // ============================================================

    public synchronized void connect(NetworkType networkType) {
        NetworkType net = defaultNet(networkType);

        if (shuttingDown) return;
        if (sockets.get(net) != null) return;

        Request req = new Request.Builder()
                .url(wsUrl(net))
                .build();

        WebSocket ws = client.newWebSocket(req, new BybitListener(net));
        sockets.put(net, ws);

        rawTopicsByNet.computeIfAbsent(net, __ -> ConcurrentHashMap.newKeySet());
        specsByTopicByNet.computeIfAbsent(net, __ -> new ConcurrentHashMap<>());
        topicRefCountByNet.computeIfAbsent(net, __ -> new ConcurrentHashMap<>());

        log.info("🔌 BYBIT WS CONNECT net={} url={}", net, wsUrl(net));
    }

    public synchronized void disconnect(NetworkType networkType) {
        NetworkType net = defaultNet(networkType);

        WebSocket ws = sockets.remove(net);
        if (ws != null) {
            try { ws.close(1000, "shutdown"); } catch (Exception ignored) {
                try { ws.cancel(); } catch (Exception ignored2) {}
            }
        }

        ScheduledFuture<?> task = reconnectTasks.remove(net);
        if (task != null) {
            try { task.cancel(false); } catch (Exception ignored) {}
        }

        rawTopicsByNet.remove(net);
        specsByTopicByNet.remove(net);
        topicRefCountByNet.remove(net);

        log.info("🔌 BYBIT WS DISCONNECT net={}", net);
    }

    public synchronized void connect() {
        connect(NetworkType.MAINNET);
    }

    public synchronized void disconnect() {
        disconnect(NetworkType.MAINNET);
    }

    public boolean isConnected(NetworkType networkType) {
        return sockets.get(defaultNet(networkType)) != null;
    }

    public boolean isConnected() {
        return isConnected(NetworkType.MAINNET);
    }

    // ============================================================
    // LEGACY TICKER API
    // ============================================================

    public synchronized void subscribeTicker(NetworkType networkType, String symbol) {
        NetworkType net = defaultNet(networkType);
        String sym = normalizeSymbol(symbol);
        if (sym == null) return;

        String topic = buildTickerTopic(sym);
        Set<String> raw = rawTopicsByNet.computeIfAbsent(net, __ -> ConcurrentHashMap.newKeySet());
        if (!raw.add(topic)) return;

        acquireTopic(net, topic);
    }

    public synchronized void unsubscribeTicker(NetworkType networkType, String symbol) {
        NetworkType net = defaultNet(networkType);
        String sym = normalizeSymbol(symbol);
        if (sym == null) return;

        String topic = buildTickerTopic(sym);
        Set<String> raw = rawTopicsByNet.computeIfAbsent(net, __ -> ConcurrentHashMap.newKeySet());
        if (!raw.remove(topic)) return;

        releaseTopic(net, topic);
    }

    public synchronized void subscribeTicker(String symbol) {
        subscribeTicker(NetworkType.MAINNET, symbol);
    }

    public synchronized void unsubscribeTicker(String symbol) {
        unsubscribeTicker(NetworkType.MAINNET, symbol);
    }

    // ============================================================
    // STRATEGY-AWARE API (как у Binance client)
    // ============================================================

    public void subscribeAggTrade(NetworkType networkType,
                                  String symbol,
                                  String timeframeIgnored,
                                  long chatId,
                                  StrategyType strategyType) {

        NetworkType net = defaultNet(networkType);
        String sym = normalizeSymbol(symbol);
        if (sym == null || strategyType == null) return;

        SubscriptionSpec spec = SubscriptionSpec.aggTrade(chatId, strategyType, sym, net);
        addSpec(spec);
    }

    public void unsubscribeAggTrade(NetworkType networkType,
                                    String symbol,
                                    String timeframeIgnored,
                                    long chatId,
                                    StrategyType strategyType) {

        NetworkType net = defaultNet(networkType);
        String sym = normalizeSymbol(symbol);
        if (sym == null || strategyType == null) return;

        SubscriptionSpec spec = SubscriptionSpec.aggTrade(chatId, strategyType, sym, net);
        removeSpec(spec);
    }

    public void subscribeKline(NetworkType networkType,
                               String symbol,
                               String timeframe,
                               long chatId,
                               StrategyType strategyType) {

        NetworkType net = defaultNet(networkType);
        String sym = normalizeSymbol(symbol);
        String tf = normalizeTimeframe(timeframe);

        if (sym == null || tf == null || strategyType == null) return;

        SubscriptionSpec spec = SubscriptionSpec.kline(chatId, strategyType, sym, tf, net);
        addSpec(spec);
    }

    public void unsubscribeKline(NetworkType networkType,
                                 String symbol,
                                 String timeframe,
                                 long chatId,
                                 StrategyType strategyType) {

        NetworkType net = defaultNet(networkType);
        String sym = normalizeSymbol(symbol);
        String tf = normalizeTimeframe(timeframe);

        if (sym == null || tf == null || strategyType == null) return;

        SubscriptionSpec spec = SubscriptionSpec.kline(chatId, strategyType, sym, tf, net);
        removeSpec(spec);
    }

    public void subscribeBookTicker(NetworkType networkType,
                                    String symbol,
                                    long chatId,
                                    StrategyType strategyType) {

        NetworkType net = defaultNet(networkType);
        String sym = normalizeSymbol(symbol);
        if (sym == null || strategyType == null) return;

        SubscriptionSpec spec = SubscriptionSpec.bookTicker(chatId, strategyType, sym, net);
        addSpec(spec);
    }

    public void unsubscribeBookTicker(NetworkType networkType,
                                      String symbol,
                                      long chatId,
                                      StrategyType strategyType) {

        NetworkType net = defaultNet(networkType);
        String sym = normalizeSymbol(symbol);
        if (sym == null || strategyType == null) return;

        SubscriptionSpec spec = SubscriptionSpec.bookTicker(chatId, strategyType, sym, net);
        removeSpec(spec);
    }

    public boolean isConnected(long chatId,
                               StrategyType strategyType,
                               NetworkType networkType,
                               String symbol,
                               String timeframe,
                               String channel) {

        NetworkType net = defaultNet(networkType);
        String sym = normalizeSymbol(symbol);
        if (chatId <= 0 || strategyType == null || sym == null || channel == null) return false;
        if (!isConnected(net)) return false;

        String ch = channel.trim().toUpperCase(Locale.ROOT);

        return switch (ch) {
            case "AGG_TRADE" -> hasSpec(SubscriptionSpec.aggTrade(chatId, strategyType, sym, net));
            case "BOOK_TICKER" -> hasSpec(SubscriptionSpec.bookTicker(chatId, strategyType, sym, net));
            case "KLINE" -> {
                String tf = normalizeTimeframe(timeframe);
                if (tf == null) yield false;
                yield hasSpec(SubscriptionSpec.kline(chatId, strategyType, sym, tf, net));
            }
            default -> false;
        };
    }

    public Long getLastMessageAt(String key) {
        return key != null ? lastMessageAt.get(key) : null;
    }

    // ============================================================
    // INTERNAL SUBSCRIPTIONS
    // ============================================================

    private void addSpec(SubscriptionSpec spec) {
        if (spec == null || shuttingDown) return;

        Map<String, Set<SubscriptionSpec>> byTopic =
                specsByTopicByNet.computeIfAbsent(spec.networkType, __ -> new ConcurrentHashMap<>());

        Set<SubscriptionSpec> set =
                byTopic.computeIfAbsent(spec.topic, __ -> ConcurrentHashMap.newKeySet());

        boolean added = set.add(spec);
        if (!added) return;

        acquireTopic(spec.networkType, spec.topic);
        log.info("📡 [BYBIT] SUBSCRIBE key={} topic={}", spec.key, spec.topic);
    }

    private void removeSpec(SubscriptionSpec spec) {
        if (spec == null) return;

        Map<String, Set<SubscriptionSpec>> byTopic = specsByTopicByNet.get(spec.networkType);
        if (byTopic == null) return;

        Set<SubscriptionSpec> set = byTopic.get(spec.topic);
        if (set == null) return;

        boolean removed = set.remove(spec);
        if (!removed) return;

        if (set.isEmpty()) {
            byTopic.remove(spec.topic);
        }

        lastMessageAt.remove(spec.key);
        releaseTopic(spec.networkType, spec.topic);

        log.info("📴 [BYBIT] UNSUBSCRIBE key={} topic={}", spec.key, spec.topic);
    }

    private boolean hasSpec(SubscriptionSpec spec) {
        if (spec == null) return false;
        Map<String, Set<SubscriptionSpec>> byTopic = specsByTopicByNet.get(spec.networkType);
        if (byTopic == null) return false;
        Set<SubscriptionSpec> set = byTopic.get(spec.topic);
        return set != null && set.contains(spec);
    }

    private void acquireTopic(NetworkType net, String topic) {
        if (topic == null || topic.isBlank()) return;

        connect(net);

        Map<String, Integer> refs = topicRefCountByNet.computeIfAbsent(net, __ -> new ConcurrentHashMap<>());
        Integer newCount = refs.merge(topic, 1, Integer::sum);

        if (newCount != null && newCount == 1) {
            send(net, "subscribe", topic);
        }
    }

    private void releaseTopic(NetworkType net, String topic) {
        if (topic == null || topic.isBlank()) return;

        Map<String, Integer> refs = topicRefCountByNet.computeIfAbsent(net, __ -> new ConcurrentHashMap<>());
        Integer after = refs.compute(topic, (t, old) -> {
            if (old == null || old <= 1) return null;
            return old - 1;
        });

        if (after == null) {
            send(net, "unsubscribe", topic);
        }
    }

    private boolean hasDesiredTopics(NetworkType net) {
        Map<String, Integer> refs = topicRefCountByNet.get(net);
        return refs != null && !refs.isEmpty();
    }

    private List<String> currentTopics(NetworkType net) {
        Map<String, Integer> refs = topicRefCountByNet.get(net);
        if (refs == null || refs.isEmpty()) return List.of();
        return new ArrayList<>(refs.keySet());
    }

    private List<SubscriptionSpec> currentSpecs(NetworkType net, String topic, Channel channel) {
        Map<String, Set<SubscriptionSpec>> byTopic = specsByTopicByNet.get(net);
        if (byTopic == null) return List.of();

        Set<SubscriptionSpec> set = byTopic.get(topic);
        if (set == null || set.isEmpty()) return List.of();

        List<SubscriptionSpec> out = new ArrayList<>(set.size());
        for (SubscriptionSpec spec : set) {
            if (spec != null && spec.channel == channel) {
                out.add(spec);
            }
        }
        return out;
    }

    // ============================================================
    // WS SEND / RECONNECT
    // ============================================================

    private void send(NetworkType net, String op, String topic) {
        WebSocket ws = sockets.get(net);
        if (ws == null) {
            log.debug("⏭ [BYBIT] send skipped, no socket net={} topic={}", net, topic);
            return;
        }

        JSONObject req = new JSONObject()
                .put("op", op)
                .put("args", new JSONArray().put(topic));

        try {
            ws.send(req.toString());
        } catch (Exception e) {
            log.warn("⚠️ [BYBIT] send failed net={} op={} topic={} err={}", net, op, topic, e.getMessage());
        }
    }

    private void scheduleReconnect(NetworkType net, String reason) {
        if (shuttingDown || !reconnectEnabled || !hasDesiredTopics(net)) return;

        ScheduledFuture<?> existing = reconnectTasks.get(net);
        if (existing != null && !existing.isDone() && !existing.isCancelled()) {
            return;
        }

        long delay = Math.max(500L, reconnectDelayMs);

        ScheduledFuture<?> future = reconnectExecutor.schedule(() -> {
            reconnectTasks.remove(net);

            if (shuttingDown || !hasDesiredTopics(net)) return;
            if (sockets.get(net) != null) return;

            log.warn("🔁 [BYBIT] reconnect net={} reason={} delayMs={}", net, reason, delay);
            connect(net);
        }, delay, TimeUnit.MILLISECONDS);

        reconnectTasks.put(net, future);
    }

    // ============================================================
    // LISTENER
    // ============================================================

    private final class BybitListener extends WebSocketListener {

        private final NetworkType net;

        private BybitListener(NetworkType net) {
            this.net = defaultNet(net);
        }

        @Override
        public void onOpen(WebSocket ws, Response response) {
            log.info("✅ BYBIT WS OPEN net={}", net);

            ScheduledFuture<?> task = reconnectTasks.remove(net);
            if (task != null) {
                try { task.cancel(false); } catch (Exception ignored) {}
            }

            for (String topic : currentTopics(net)) {
                send(net, "subscribe", topic);
            }
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            try {
                JSONObject obj = new JSONObject(text);

                String op = obj.optString("op", "");
                if ("pong".equalsIgnoreCase(op)) return;

                if ("ping".equalsIgnoreCase(op)) {
                    try {
                        ws.send(new JSONObject().put("op", "pong").toString());
                    } catch (Exception ignored) {}
                    return;
                }

                if (obj.has("success")) {
                    boolean ok = obj.optBoolean("success", true);
                    if (!ok) {
                        log.warn("⚠️ [BYBIT] WS ACK FAILED net={} msg={}", net, text);
                    }
                }

                String topic = obj.optString("topic", "");
                if (topic.isBlank()) return;

                if (topic.startsWith("publicTrade.")) {
                    parseTrade(topic, obj, net);
                    return;
                }

                if (topic.startsWith("tickers.")) {
                    parseTicker(topic, obj, net);
                    return;
                }

                if (topic.startsWith("kline.")) {
                    parseKline(topic, obj, net);
                }

            } catch (Exception e) {
                log.error("❌ BYBIT WS parse error net={} err={}", net, e.getMessage(), e);
            }
        }

        @Override
        public void onMessage(WebSocket ws, ByteString bytes) {
            onMessage(ws, bytes.utf8());
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            log.warn("⚠️ BYBIT WS CLOSING net={} code={} reason={}", net, code, reason);
            try { ws.close(code, reason); } catch (Exception ignored) {}
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            sockets.compute(net, (k, cur) -> cur == ws ? null : cur);
            log.warn("❌ BYBIT WS CLOSED net={} code={} reason={}", net, code, reason);
            scheduleReconnect(net, "closed:" + code);
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            sockets.compute(net, (k, cur) -> cur == ws ? null : cur);
            log.error("❌ BYBIT WS FAILURE net={} err={}", net, t.toString(), t);
            scheduleReconnect(net, "failure");
        }
    }

    // ============================================================
    // PARSERS
    // ============================================================

    private void parseTrade(String topic, JSONObject obj, NetworkType net) {
        Object dataNode = obj.opt("data");
        if (dataNode == null) return;

        String fallbackSymbol = symbolFromTopic(topic);
        long now = System.currentTimeMillis();

        if (dataNode instanceof JSONArray arr) {
            for (int i = 0; i < arr.length(); i++) {
                Object node = arr.get(i);
                if (node instanceof JSONObject trade) {
                    emitTrade(topic, trade, net, fallbackSymbol, now);
                }
            }
            return;
        }

        if (dataNode instanceof JSONObject trade) {
            emitTrade(topic, trade, net, fallbackSymbol, now);
        }
    }

    private void emitTrade(String topic,
                           JSONObject trade,
                           NetworkType net,
                           String fallbackSymbol,
                           long now) {

        String symbol = firstNonBlank(
                normalizeSymbol(trade.optString("s", null)),
                normalizeSymbol(trade.optString("symbol", null)),
                fallbackSymbol
        );
        if (symbol == null) return;

        BigDecimal price = firstDecimal(trade, "p", "price");
        BigDecimal qty = firstDecimal(trade, "v", "size", "q");
        if (price == null || price.signum() <= 0) return;
        if (qty == null) qty = BigDecimal.ZERO;

        long ts = firstPositive(
                trade.optLong("T", 0L),
                trade.optLong("ts", 0L),
                trade.optLong("t", 0L),
                now
        );

        try {
            router.route(new Tick("BYBIT", symbol, price, ts));
        } catch (Exception ignored) {
        }

        List<SubscriptionSpec> specs = currentSpecs(net, topic, Channel.AGG_TRADE);
        for (SubscriptionSpec spec : specs) {
            touch(spec.key, now);

            try {
                marketStream.onAggTrade(
                        spec.chatId,
                        spec.strategyType,
                        "BYBIT",
                        net,
                        spec.symbolUpper,
                        price,
                        qty,
                        ts
                );
            } catch (Exception e) {
                log.warn("⚠️ [BYBIT] trade dispatch failed key={} err={}", spec.key, e.getMessage());
            }
        }
    }

    private void parseTicker(String topic, JSONObject obj, NetworkType net) {
        Object node = obj.opt("data");
        if (node == null) return;

        JSONObject data = firstObject(node);
        if (data == null) return;

        String symbol = firstNonBlank(
                normalizeSymbol(data.optString("symbol", null)),
                symbolFromTopic(topic)
        );
        if (symbol == null) return;

        BigDecimal bid = firstDecimal(data, "bid1Price", "bidPrice", "b");
        BigDecimal ask = firstDecimal(data, "ask1Price", "askPrice", "a");
        BigDecimal last = firstDecimal(data, "lastPrice", "markPrice");

        BigDecimal priceForRouter = last;
        if (priceForRouter == null || priceForRouter.signum() <= 0) {
            if (bid != null && ask != null && bid.signum() > 0 && ask.signum() > 0) {
                priceForRouter = bid.add(ask).divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP);
            } else if (bid != null && bid.signum() > 0) {
                priceForRouter = bid;
            } else if (ask != null && ask.signum() > 0) {
                priceForRouter = ask;
            }
        }

        long now = System.currentTimeMillis();
        long ts = firstPositive(
                obj.optLong("ts", 0L),
                data.optLong("ts", 0L),
                now
        );

        if (priceForRouter != null && priceForRouter.signum() > 0) {
            try {
                router.route(new Tick("BYBIT", symbol, priceForRouter, ts));
            } catch (Exception ignored) {
            }
        }

        List<SubscriptionSpec> specs = currentSpecs(net, topic, Channel.BOOK_TICKER);
        for (SubscriptionSpec spec : specs) {
            touch(spec.key, now);

            try {
                marketStream.onBookTicker(
                        spec.chatId,
                        spec.strategyType,
                        "BYBIT",
                        net,
                        spec.symbolUpper,
                        bid,
                        ask,
                        ts
                );
            } catch (Exception e) {
                log.warn("⚠️ [BYBIT] ticker dispatch failed key={} err={}", spec.key, e.getMessage());
            }
        }
    }

    private void parseKline(String topic, JSONObject obj, NetworkType net) {
        UnifiedKline kline = klineParser.parse(obj);
        if (kline == null) return;

        String fallbackSymbol = symbolFromTopic(topic);
        long now = System.currentTimeMillis();

        if (kline.getSymbol() == null || kline.getSymbol().isBlank()) {
            kline.setSymbol(fallbackSymbol);
        }

        try {
            router.routeKline("BYBIT", kline);
        } catch (Exception ignored) {
        }

        List<SubscriptionSpec> specs = currentSpecs(net, topic, Channel.KLINE);
        for (SubscriptionSpec spec : specs) {
            touch(spec.key, now);

            try {
                kline.setSymbol(spec.symbolUpper);
                kline.setTimeframe(spec.timeframeLower);

                marketStream.onKline(
                        spec.chatId,
                        spec.strategyType,
                        "BYBIT",
                        net,
                        spec.symbolUpper,
                        spec.timeframeLower,
                        kline
                );
            } catch (Exception e) {
                log.warn("⚠️ [BYBIT] kline dispatch failed key={} err={}", spec.key, e.getMessage());
            }
        }
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private void touch(String key, long now) {
        if (key == null) return;
        lastMessageAt.put(key, now > 0 ? now : System.currentTimeMillis());
    }

    private static NetworkType defaultNet(NetworkType net) {
        return net != null ? net : NetworkType.MAINNET;
    }

    private static String wsUrl(NetworkType net) {
        return net == NetworkType.TESTNET ? WS_URL_TESTNET : WS_URL_MAINNET;
    }

    private static String normalizeSymbol(String s) {
        if (s == null) return null;
        String x = s.replace("/", "").trim().toUpperCase(Locale.ROOT);
        return x.isEmpty() ? null : x;
    }

    private static String normalizeTimeframe(String tf) {
        if (tf == null) return null;
        String x = tf.trim().toLowerCase(Locale.ROOT);
        return x.isEmpty() ? null : x;
    }

    private static String bybitInterval(String timeframe) {
        String tf = normalizeTimeframe(timeframe);
        if (tf == null) return null;

        return switch (tf) {
            case "1", "1m" -> "1";
            case "3", "3m" -> "3";
            case "5", "5m" -> "5";
            case "15", "15m" -> "15";
            case "30", "30m" -> "30";
            case "60", "1h" -> "60";
            case "120", "2h" -> "120";
            case "240", "4h" -> "240";
            case "360", "6h" -> "360";
            case "720", "12h" -> "720";
            case "d", "1d" -> "D";
            case "w", "1w" -> "W";
            case "m", "1mo", "1mon", "1month" -> "M";
            default -> tf;
        };
    }

    private static String buildTradeTopic(String symbolUpper) {
        return "publicTrade." + symbolUpper;
    }

    private static String buildTickerTopic(String symbolUpper) {
        return "tickers." + symbolUpper;
    }

    private static String buildKlineTopic(String symbolUpper, String timeframeLower) {
        return "kline." + bybitInterval(timeframeLower) + "." + symbolUpper;
    }

    private static String symbolFromTopic(String topic) {
        if (topic == null || topic.isBlank()) return null;
        int idx = topic.lastIndexOf('.');
        if (idx < 0 || idx + 1 >= topic.length()) return null;
        return normalizeSymbol(topic.substring(idx + 1));
    }

    private static JSONObject firstObject(Object node) {
        if (node == null) return null;
        if (node instanceof JSONObject jo) return jo;
        if (node instanceof JSONArray arr) {
            if (arr.isEmpty()) return null;
            Object first = arr.get(0);
            if (first instanceof JSONObject jo) return jo;
        }
        return null;
    }

    private static BigDecimal firstDecimal(JSONObject o, String... keys) {
        if (o == null || keys == null) return null;
        for (String key : keys) {
            if (key == null || !o.has(key) || o.isNull(key)) continue;
            try {
                String s = String.valueOf(o.get(key)).trim();
                if (!s.isEmpty()) {
                    return new BigDecimal(s);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static long firstPositive(long... values) {
        if (values == null) return 0L;
        for (long v : values) {
            if (v > 0) return v;
        }
        return 0L;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    // ============================================================
    // KEYS / SUBSCRIPTION SPEC
    // ============================================================

    private enum Channel {
        AGG_TRADE,
        BOOK_TICKER,
        KLINE
    }

    private static final class SubscriptionSpec {
        private final String key;
        private final long chatId;
        private final StrategyType strategyType;
        private final String symbolUpper;
        private final String timeframeLower;
        private final NetworkType networkType;
        private final Channel channel;
        private final String topic;

        private SubscriptionSpec(String key,
                                 long chatId,
                                 StrategyType strategyType,
                                 String symbolUpper,
                                 String timeframeLower,
                                 NetworkType networkType,
                                 Channel channel,
                                 String topic) {
            this.key = key;
            this.chatId = chatId;
            this.strategyType = strategyType;
            this.symbolUpper = symbolUpper;
            this.timeframeLower = timeframeLower;
            this.networkType = networkType;
            this.channel = channel;
            this.topic = topic;
        }

        private static SubscriptionSpec aggTrade(long chatId,
                                                 StrategyType strategyType,
                                                 String symbolUpper,
                                                 NetworkType net) {
            return new SubscriptionSpec(
                    buildKeyAgg(chatId, strategyType, symbolUpper, net),
                    chatId,
                    strategyType,
                    symbolUpper,
                    null,
                    net,
                    Channel.AGG_TRADE,
                    buildTradeTopic(symbolUpper)
            );
        }

        private static SubscriptionSpec bookTicker(long chatId,
                                                   StrategyType strategyType,
                                                   String symbolUpper,
                                                   NetworkType net) {
            return new SubscriptionSpec(
                    buildKeyBook(chatId, strategyType, symbolUpper, net),
                    chatId,
                    strategyType,
                    symbolUpper,
                    null,
                    net,
                    Channel.BOOK_TICKER,
                    buildTickerTopic(symbolUpper)
            );
        }

        private static SubscriptionSpec kline(long chatId,
                                              StrategyType strategyType,
                                              String symbolUpper,
                                              String timeframeLower,
                                              NetworkType net) {
            return new SubscriptionSpec(
                    buildKeyKline(chatId, strategyType, symbolUpper, timeframeLower, net),
                    chatId,
                    strategyType,
                    symbolUpper,
                    timeframeLower,
                    net,
                    Channel.KLINE,
                    buildKlineTopic(symbolUpper, timeframeLower)
            );
        }

        @Override
        public int hashCode() {
            return Objects.hash(key);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof SubscriptionSpec other)) return false;
            return Objects.equals(this.key, other.key);
        }
    }

    private static String buildKeyAgg(long chatId,
                                      StrategyType strategyType,
                                      String symUpper,
                                      NetworkType net) {
        return "BYBIT:" + net + ":" + chatId + ":" + strategyType.name() + ":" + symUpper + ":AGG_TRADE";
    }

    private static String buildKeyBook(long chatId,
                                       StrategyType strategyType,
                                       String symUpper,
                                       NetworkType net) {
        return "BYBIT:" + net + ":" + chatId + ":" + strategyType.name() + ":" + symUpper + ":BOOK_TICKER";
    }

    private static String buildKeyKline(long chatId,
                                        StrategyType strategyType,
                                        String symUpper,
                                        String tfLower,
                                        NetworkType net) {
        return "BYBIT:" + net + ":" + chatId + ":" + strategyType.name() + ":" + symUpper + ":" + tfLower + ":KLINE";
    }
}
