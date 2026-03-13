package com.chicu.aitradebot.exchange.binance.ws;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.parser.BinanceKlineParser;
import com.chicu.aitradebot.market.MarketStreamService;
import com.chicu.aitradebot.market.model.UnifiedKline;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceSpotWebSocketClient {

    private static final String WS_MAIN_STREAM_TEMPLATE =
            "wss://stream.binance.com:9443/stream?streams=%s";

    private static final String WS_TEST_STREAM_TEMPLATE =
            "wss://stream.testnet.binance.vision/stream?streams=%s";

    @Value("${exchange.binance.ws.reconnect.enabled:true}")
    private boolean reconnectEnabled;

    @Value("${exchange.binance.ws.reconnect.initialDelayMs:1000}")
    private long reconnectInitialDelayMs;

    @Value("${exchange.binance.ws.reconnect.maxDelayMs:30000}")
    private long reconnectMaxDelayMs;

    @Value("${exchange.binance.ws.reconnect.jitterMs:350}")
    private long reconnectJitterMs;

    @Value("${exchange.binance.ws.reconnect.maxAttempts:0}")
    private int reconnectMaxAttempts;

    private final OkHttpClient client;
    private final BinanceKlineParser klineParser;
    private final MarketStreamService marketStream;

    /**
     * Активные сокеты по ключу.
     */
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();

    /**
     * Желаемые подписки.
     * Если ключ есть здесь — reconnect разрешён.
     * Если ключ удалён отсюда — reconnect запрещён.
     */
    private final Map<String, SubscriptionSpec> subscriptions = new ConcurrentHashMap<>();

    /**
     * Запланированные reconnect-задачи.
     */
    private final Map<String, ScheduledFuture<?>> reconnectTasks = new ConcurrentHashMap<>();

    /**
     * Счётчики попыток reconnect.
     */
    private final Map<String, Integer> reconnectAttempts = new ConcurrentHashMap<>();

    /**
     * Время последнего входящего сообщения по ключу.
     */
    private final Map<String, Long> lastMessageAt = new ConcurrentHashMap<>();

    /**
     * Для отладочных логов KLINE.
     */
    private final Map<String, Integer> klineRxCount = new ConcurrentHashMap<>();

    /**
     * Локи по ключу, чтобы не плодить дубль-сокеты при гонках.
     */
    private final Map<String, Object> keyLocks = new ConcurrentHashMap<>();

    private final ScheduledExecutorService reconnectExecutor = Executors.newScheduledThreadPool(
            Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())),
            new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger(1);

                @Override
                public Thread newThread(@NotNull Runnable r) {
                    Thread t = new Thread(r, "binance-ws-reconnect-" + seq.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            }
    );

    private volatile boolean shuttingDown = false;

    // =====================================================
    // PUBLIC API
    // =====================================================

    public void subscribeAggTrade(NetworkType networkType,
                                  String symbol,
                                  String timeframeIgnored,
                                  long chatId,
                                  StrategyType strategyType) {

        NetworkType net = (networkType != null) ? networkType : NetworkType.MAINNET;
        String sym = normSymbolLowerSafe(symbol);

        if (sym == null || strategyType == null) return;

        SubscriptionSpec spec = SubscriptionSpec.aggTrade(chatId, strategyType, sym, net);
        ensureSubscribed(spec);
    }

    public void unsubscribeAggTrade(NetworkType networkType,
                                    String symbol,
                                    String timeframeIgnored,
                                    long chatId,
                                    StrategyType strategyType) {

        NetworkType net = (networkType != null) ? networkType : NetworkType.MAINNET;
        String sym = normSymbolLowerSafe(symbol);

        if (sym == null || strategyType == null) return;

        String key = buildKeyAgg(chatId, strategyType, sym, net);
        closeAndRemove(key, "unsubscribe");
    }

    public void subscribeKline(NetworkType networkType,
                               String symbol,
                               String timeframe,
                               long chatId,
                               StrategyType strategyType) {

        NetworkType net = (networkType != null) ? networkType : NetworkType.MAINNET;
        String sym = normSymbolLowerSafe(symbol);
        String tf = normTfLowerSafe(timeframe);

        if (sym == null || tf == null || strategyType == null) return;

        SubscriptionSpec spec = SubscriptionSpec.kline(chatId, strategyType, sym, tf, net);
        ensureSubscribed(spec);
    }

    public void unsubscribeKline(NetworkType networkType,
                                 String symbol,
                                 String timeframe,
                                 long chatId,
                                 StrategyType strategyType) {

        NetworkType net = (networkType != null) ? networkType : NetworkType.MAINNET;
        String sym = normSymbolLowerSafe(symbol);
        String tf = normTfLowerSafe(timeframe);

        if (sym == null || tf == null || strategyType == null) return;

        String key = buildKeyKline(chatId, strategyType, sym, tf, net);
        closeAndRemove(key, "unsubscribe");
    }

    public void subscribeBookTicker(NetworkType networkType,
                                    String symbol,
                                    long chatId,
                                    StrategyType strategyType) {

        NetworkType net = (networkType != null) ? networkType : NetworkType.MAINNET;
        String sym = normSymbolLowerSafe(symbol);

        if (sym == null || strategyType == null) return;

        SubscriptionSpec spec = SubscriptionSpec.bookTicker(chatId, strategyType, sym, net);
        ensureSubscribed(spec);
    }

    public void unsubscribeBookTicker(NetworkType networkType,
                                      String symbol,
                                      long chatId,
                                      StrategyType strategyType) {

        NetworkType net = (networkType != null) ? networkType : NetworkType.MAINNET;
        String sym = normSymbolLowerSafe(symbol);

        if (sym == null || strategyType == null) return;

        String key = buildKeyBook(chatId, strategyType, sym, net);
        closeAndRemove(key, "unsubscribe");
    }

    public boolean isConnected(long chatId,
                               StrategyType strategyType,
                               NetworkType networkType,
                               String symbol,
                               String timeframe,
                               String channel) {
        String sym = normSymbolLowerSafe(symbol);
        if (sym == null || strategyType == null) return false;

        NetworkType net = (networkType != null) ? networkType : NetworkType.MAINNET;
        String ch = channel == null ? "" : channel.trim().toUpperCase(Locale.ROOT);

        String key;
        switch (ch) {
            case "AGG_TRADE" -> key = buildKeyAgg(chatId, strategyType, sym, net);
            case "BOOK_TICKER" -> key = buildKeyBook(chatId, strategyType, sym, net);
            case "KLINE" -> {
                String tf = normTfLowerSafe(timeframe);
                if (tf == null) return false;
                key = buildKeyKline(chatId, strategyType, sym, tf, net);
            }
            default -> {
                return false;
            }
        }

        return sockets.containsKey(key);
    }

    public Long getLastMessageAt(String key) {
        return key != null ? lastMessageAt.get(key) : null;
    }

    public int getActiveSocketCount() {
        return sockets.size();
    }

    @PreDestroy
    public void shutdown() {
        shuttingDown = true;

        for (Map.Entry<String, ScheduledFuture<?>> e : reconnectTasks.entrySet()) {
            try {
                e.getValue().cancel(false);
            } catch (Exception ignored) {
            }
        }
        reconnectTasks.clear();

        for (Map.Entry<String, WebSocket> e : sockets.entrySet()) {
            try {
                e.getValue().close(1001, "shutdown");
            } catch (Exception ignored) {
            }
        }
        sockets.clear();
        subscriptions.clear();
        reconnectAttempts.clear();
        lastMessageAt.clear();
        klineRxCount.clear();

        try {
            reconnectExecutor.shutdownNow();
        } catch (Exception ignored) {
        }
    }

    // =====================================================
    // SUBSCRIBE / CONNECT
    // =====================================================

    private void ensureSubscribed(SubscriptionSpec spec) {
        if (spec == null || shuttingDown) return;

        subscriptions.put(spec.key, spec);
        cancelReconnect(spec.key);
        connect(spec);
    }

    private void connect(SubscriptionSpec spec) {
        if (spec == null || shuttingDown) return;
        if (!isDesired(spec.key)) return;

        Object lock = keyLocks.computeIfAbsent(spec.key, k -> new Object());

        synchronized (lock) {
            if (shuttingDown) return;
            if (!isDesired(spec.key)) return;
            if (sockets.containsKey(spec.key)) return;

            String wsUrl = buildWsUrl(spec.networkType, spec.streams);

            Request request = new Request.Builder().url(wsUrl).build();

            log.info("🔌 WS CONNECT BINANCE chatId={} type={} sym={}{} net={} channel={} url={}",
                    spec.chatId,
                    spec.strategyType,
                    spec.symbolLower.toUpperCase(Locale.ROOT),
                    spec.timeframeLower != null ? " tf=" + spec.timeframeLower : "",
                    spec.networkType,
                    spec.channel.name(),
                    wsUrl);

            WebSocket ws = client.newWebSocket(request, createListener(spec));
            WebSocket prev = sockets.putIfAbsent(spec.key, ws);

            if (prev != null) {
                try {
                    ws.close(1000, "duplicate");
                } catch (Exception ignored) {
                }
            }
        }
    }

    private WebSocketListener createListener(SubscriptionSpec spec) {
        return switch (spec.channel) {
            case AGG_TRADE -> new AggTradeListener(spec);
            case BOOK_TICKER -> new BookTickerListener(spec);
            case KLINE -> new KlineListener(spec);
        };
    }

    private void scheduleReconnect(SubscriptionSpec spec, String trigger) {
        if (spec == null || shuttingDown || !reconnectEnabled) return;
        if (!isDesired(spec.key)) return;

        ScheduledFuture<?> existing = reconnectTasks.get(spec.key);
        if (existing != null && !existing.isDone() && !existing.isCancelled()) {
            return;
        }

        int attempt = reconnectAttempts.merge(spec.key, 1, Integer::sum);

        if (reconnectMaxAttempts > 0 && attempt > reconnectMaxAttempts) {
            log.error("🛑 WS RECONNECT LIMIT BINANCE key={} attempts={} trigger={}",
                    spec.key, attempt - 1, trigger);
            reconnectTasks.remove(spec.key);
            subscriptions.remove(spec.key);
            sockets.remove(spec.key);
            return;
        }

        long delay = computeReconnectDelayMs(attempt);

        ScheduledFuture<?> future = reconnectExecutor.schedule(() -> {
            reconnectTasks.remove(spec.key);

            if (shuttingDown || !isDesired(spec.key)) {
                return;
            }

            if (sockets.containsKey(spec.key)) {
                return;
            }

            log.warn("🔁 WS RECONNECT BINANCE key={} attempt={} delayMs={} trigger={}",
                    spec.key, attempt, delay, trigger);

            connect(spec);

        }, delay, TimeUnit.MILLISECONDS);

        reconnectTasks.put(spec.key, future);
    }

    private long computeReconnectDelayMs(int attempt) {
        long initial = Math.max(250L, reconnectInitialDelayMs);
        long max = Math.max(initial, reconnectMaxDelayMs);

        long backoff;
        if (attempt <= 1) {
            backoff = initial;
        } else {
            long mult = 1L << Math.min(20, attempt - 1);
            if (mult <= 0) mult = 1L;

            if (initial > Long.MAX_VALUE / mult) {
                backoff = max;
            } else {
                backoff = initial * mult;
            }
        }

        backoff = Math.min(backoff, max);

        long jitter = Math.max(0L, reconnectJitterMs);
        if (jitter > 0) {
            backoff += ThreadLocalRandom.current().nextLong(jitter + 1L);
        }

        return backoff;
    }

    private boolean isDesired(String key) {
        return key != null && subscriptions.containsKey(key);
    }

    private void onSocketOpened(SubscriptionSpec spec) {
        reconnectAttempts.remove(spec.key);
        cancelReconnect(spec.key);
        lastMessageAt.put(spec.key, System.currentTimeMillis());
        if (spec.channel == Channel.KLINE) {
            klineRxCount.remove(spec.key);
        }
        log.info("✅ WS OPEN BINANCE key={}", spec.key);
    }

    private void onSocketClosing(SubscriptionSpec spec, int code, String reason) {
        log.warn("⚠️ WS CLOSING BINANCE key={} code={} reason={}", spec.key, code, reason);
    }

    private void onSocketClosed(SubscriptionSpec spec, WebSocket webSocket, int code, String reason) {
        removeIfSame(spec.key, webSocket);

        if (isDesired(spec.key) && !shuttingDown) {
            log.error("❌ WS CLOSED BINANCE key={} code={} reason={} -> reconnect", spec.key, code, reason);
            scheduleReconnect(spec, "closed:" + code);
        } else {
            log.info("❌ WS CLOSED BINANCE key={} code={} reason={}", spec.key, code, reason);
        }
    }

    private void onSocketFailure(SubscriptionSpec spec, WebSocket webSocket, Throwable t, Response response) {
        String resp = (response != null) ? (response.code() + " " + response.message()) : "no-response";
        removeIfSame(spec.key, webSocket);

        if (isDesired(spec.key) && !shuttingDown) {
            log.error("💥 WS FAIL BINANCE key={} resp={} err={} -> reconnect",
                    spec.key, resp, t.toString());
            scheduleReconnect(spec, "failure");
        } else {
            log.error("💥 WS FAIL BINANCE key={} resp={} err={}",
                    spec.key, resp, t.toString());
        }
    }

    private void touchMessage(SubscriptionSpec spec) {
        if (spec != null) {
            lastMessageAt.put(spec.key, System.currentTimeMillis());
        }
    }

    // =====================================================
    // LISTENERS
    // =====================================================

    private abstract class BaseListener extends WebSocketListener {
        protected final SubscriptionSpec spec;

        private BaseListener(SubscriptionSpec spec) {
            this.spec = spec;
        }

        @Override
        public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
            onSocketOpened(spec);
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
            touchMessage(spec);
            handleText(text);
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
            touchMessage(spec);
            handleText(bytes.utf8());
        }

        @Override
        public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
            onSocketClosing(spec, code, reason);
        }

        @Override
        public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
            onSocketClosed(spec, webSocket, code, reason);
        }

        @Override
        public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, Response response) {
            onSocketFailure(spec, webSocket, t, response);
        }

        protected abstract void handleText(String raw);
    }

    private final class AggTradeListener extends BaseListener {

        private AggTradeListener(SubscriptionSpec spec) {
            super(spec);
        }

        @Override
        protected void handleText(String raw) {
            try {
                JSONObject root = new JSONObject(raw);
                JSONObject data = root.has("data") ? root.getJSONObject("data") : root;

                String eventType = data.optString("e", "");
                if (!"aggTrade".equalsIgnoreCase(eventType)) return;

                String symUpper = spec.symbolLower.toUpperCase(Locale.ROOT);

                BigDecimal price = new BigDecimal(data.getString("p"));
                BigDecimal qty = new BigDecimal(data.getString("q"));

                long ts = data.has("T") ? data.getLong("T")
                        : (data.has("E") ? data.getLong("E") : System.currentTimeMillis());

                marketStream.onAggTrade(
                        spec.chatId,
                        spec.strategyType,
                        "BINANCE",
                        spec.networkType,
                        symUpper,
                        price,
                        qty,
                        ts
                );

            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("WS aggTrade parse error key={} err={}", spec.key, e.toString());
                }
            }
        }
    }

    private final class BookTickerListener extends BaseListener {

        private BookTickerListener(SubscriptionSpec spec) {
            super(spec);
        }

        @Override
        protected void handleText(String raw) {
            try {
                JSONObject root = new JSONObject(raw);
                JSONObject data = root.has("data") ? root.getJSONObject("data") : root;

                String s = data.optString("s", "");
                if (s.isBlank()) {
                    s = spec.symbolLower.toUpperCase(Locale.ROOT);
                }

                String bidStr = data.optString("b", "");
                String askStr = data.optString("a", "");
                if (bidStr.isBlank() && askStr.isBlank()) return;

                BigDecimal bid = bidStr.isBlank() ? null : new BigDecimal(bidStr);
                BigDecimal ask = askStr.isBlank() ? null : new BigDecimal(askStr);

                BigDecimal price;
                if (bid != null && ask != null && bid.signum() > 0 && ask.signum() > 0) {
                    price = bid.add(ask).divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP);
                } else if (bid != null && bid.signum() > 0) {
                    price = bid;
                } else if (ask != null && ask.signum() > 0) {
                    price = ask;
                } else {
                    return;
                }

                long ts = data.has("E") ? data.getLong("E") : System.currentTimeMillis();

                marketStream.onAggTrade(
                        spec.chatId,
                        spec.strategyType,
                        "BINANCE",
                        spec.networkType,
                        s.trim().toUpperCase(Locale.ROOT),
                        price,
                        BigDecimal.ZERO,
                        ts
                );

            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("WS bookTicker parse error key={} err={}", spec.key, e.toString());
                }
            }
        }
    }

    private final class KlineListener extends BaseListener {

        private KlineListener(SubscriptionSpec spec) {
            super(spec);
        }

        @Override
        protected void handleText(String raw) {
            try {
                JSONObject root = new JSONObject(raw);
                JSONObject event = root.has("data") ? root.getJSONObject("data") : root;

                String eventType = event.optString("e", "");
                if (!"kline".equalsIgnoreCase(eventType)) return;

                int cnt = klineRxCount.merge(spec.key, 1, Integer::sum);
                if (cnt == 1 || (cnt % 30 == 0)) {
                    String stream = root.optString("stream", "");
                    log.info("🕯️ WS KLINE RX key={} cnt={} stream={}", spec.key, cnt, stream);
                }

                UnifiedKline kline = klineParser.parse(root);
                if (kline == null) return;

                String symUpper = spec.symbolLower.toUpperCase(Locale.ROOT);

                marketStream.onKline(
                        spec.chatId,
                        spec.strategyType,
                        "BINANCE",
                        spec.networkType,
                        symUpper,
                        spec.timeframeLower,
                        kline
                );

            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("WS kline parse error key={} err={}", spec.key, e.toString());
                }
            }
        }
    }

    // =====================================================
    // INTERNAL
    // =====================================================

    private void closeAndRemove(String key, String reason) {
        subscriptions.remove(key);
        cancelReconnect(key);
        reconnectAttempts.remove(key);
        lastMessageAt.remove(key);
        klineRxCount.remove(key);

        WebSocket ws = sockets.remove(key);
        if (ws != null) {
            try {
                ws.close(1000, reason);
            } catch (Exception ignored) {
            }
        }

        log.info("🔌 WS DISCONNECT BINANCE key={} reason={}", key, reason);
    }

    private void cancelReconnect(String key) {
        ScheduledFuture<?> future = reconnectTasks.remove(key);
        if (future != null) {
            try {
                future.cancel(false);
            } catch (Exception ignored) {
            }
        }
    }

    private void removeIfSame(String key, WebSocket ws) {
        if (key == null || ws == null) return;
        sockets.compute(key, (k, cur) -> (cur == ws) ? null : cur);
    }

    private static String buildKeyAgg(long chatId,
                                      StrategyType strategyType,
                                      String symLower,
                                      NetworkType net) {
        return "BINANCE:" + net + ":" + chatId + ":" + strategyType.name() + ":" + symLower + ":AGG_TRADE";
    }

    private static String buildKeyBook(long chatId,
                                       StrategyType strategyType,
                                       String symLower,
                                       NetworkType net) {
        return "BINANCE:" + net + ":" + chatId + ":" + strategyType.name() + ":" + symLower + ":BOOK_TICKER";
    }

    private static String buildKeyKline(long chatId,
                                        StrategyType strategyType,
                                        String symLower,
                                        String tfLower,
                                        NetworkType net) {
        return "BINANCE:" + net + ":" + chatId + ":" + strategyType.name() + ":" + symLower + ":" + tfLower + ":KLINE";
    }

    private static String buildWsUrl(NetworkType networkType, String streams) {
        NetworkType nt = (networkType != null) ? networkType : NetworkType.MAINNET;
        String tpl = (nt == NetworkType.TESTNET) ? WS_TEST_STREAM_TEMPLATE : WS_MAIN_STREAM_TEMPLATE;
        return String.format(tpl, streams);
    }

    private static String normSymbolLowerSafe(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toLowerCase(Locale.ROOT).replace("/", "");
        return s.isEmpty() ? null : s;
    }

    private static String normTfLowerSafe(String timeframe) {
        if (timeframe == null) return null;
        String s = timeframe.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    // =====================================================
    // SUBSCRIPTION SPEC
    // =====================================================

    private enum Channel {
        AGG_TRADE,
        BOOK_TICKER,
        KLINE
    }

    private static final class SubscriptionSpec {
        private final String key;
        private final long chatId;
        private final StrategyType strategyType;
        private final String symbolLower;
        private final String timeframeLower;
        private final NetworkType networkType;
        private final Channel channel;
        private final String streams;

        private SubscriptionSpec(String key,
                                 long chatId,
                                 StrategyType strategyType,
                                 String symbolLower,
                                 String timeframeLower,
                                 NetworkType networkType,
                                 Channel channel,
                                 String streams) {
            this.key = key;
            this.chatId = chatId;
            this.strategyType = strategyType;
            this.symbolLower = symbolLower;
            this.timeframeLower = timeframeLower;
            this.networkType = networkType;
            this.channel = channel;
            this.streams = streams;
        }

        private static SubscriptionSpec aggTrade(long chatId,
                                                 StrategyType strategyType,
                                                 String symLower,
                                                 NetworkType net) {
            return new SubscriptionSpec(
                    buildKeyAgg(chatId, strategyType, symLower, net),
                    chatId,
                    strategyType,
                    symLower,
                    null,
                    net,
                    Channel.AGG_TRADE,
                    symLower + "@aggTrade"
            );
        }

        private static SubscriptionSpec bookTicker(long chatId,
                                                   StrategyType strategyType,
                                                   String symLower,
                                                   NetworkType net) {
            return new SubscriptionSpec(
                    buildKeyBook(chatId, strategyType, symLower, net),
                    chatId,
                    strategyType,
                    symLower,
                    null,
                    net,
                    Channel.BOOK_TICKER,
                    symLower + "@bookTicker"
            );
        }

        private static SubscriptionSpec kline(long chatId,
                                              StrategyType strategyType,
                                              String symLower,
                                              String tfLower,
                                              NetworkType net) {
            return new SubscriptionSpec(
                    buildKeyKline(chatId, strategyType, symLower, tfLower, net),
                    chatId,
                    strategyType,
                    symLower,
                    tfLower,
                    net,
                    Channel.KLINE,
                    symLower + "@kline_" + tfLower
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
}