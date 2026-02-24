package com.chicu.aitradebot.market.stream;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.binance.ws.BinanceSpotWebSocketClient;
import com.chicu.aitradebot.market.MarketStreamManager;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.market.model.UnifiedKline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class MarketDataStreamService {

    private static final int MAX_CANDLES = 2_000;

    /** ✅ цикл разорван — берём лениво */
    private final ObjectProvider<BinanceSpotWebSocketClient> binanceWsProvider;

    /** ✅ общий кэш свечей для бэктеста/дашборда (env-aware если умеет) */
    private final MarketStreamManager streamManager;

    /** seq для логов/троттлинга */
    private final AtomicLong seq = new AtomicLong(0);

    /**
     * ✅ Хранилище свечей строго по ключу:
     * (chatId, type, ex, net, symbol, tf)
     */
    private final ConcurrentMap<CandleStoreKey, Deque<Candle>> candleStorage = new ConcurrentHashMap<>();

    /** chatId → set of подписок (строго с ex+net+sym+tf) */
    private final ConcurrentMap<Long, Set<SubscriptionKey>> activeSubscriptions = new ConcurrentHashMap<>();

    public MarketDataStreamService(ObjectProvider<BinanceSpotWebSocketClient> binanceWsProvider,
                                   MarketStreamManager streamManager) {
        this.binanceWsProvider = binanceWsProvider;
        this.streamManager = streamManager;
    }

    // =====================================================================
    // ✅ API ДЛЯ MarketStreamServiceImpl
    // =====================================================================

    public void subscribe(String exchange,
                          NetworkType networkType,
                          long chatId,
                          StrategyType strategyType,
                          String symbol,
                          String timeframe) {
        subscribeCandles(exchange, networkType, chatId, strategyType, symbol, timeframe);
    }

    public void unsubscribe(String exchange,
                            NetworkType networkType,
                            long chatId,
                            StrategyType strategyType,
                            String symbol,
                            String timeframe) {

        String ex  = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        if (ex == null || networkType == null || strategyType == null || sym == null || tf == null) return;

        Set<SubscriptionKey> subs = activeSubscriptions.get(chatId);
        if (subs == null || subs.isEmpty()) return;

        SubscriptionKey key = new SubscriptionKey(strategyType, ex, networkType, sym, tf);

        boolean removed = subs.remove(key);
        if (!removed) return;

        candleStorage.remove(new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf));

        if ("BINANCE".equalsIgnoreCase(ex)) {
            BinanceSpotWebSocketClient ws = binanceWsProvider.getIfAvailable();
            if (ws != null) {
                try { ws.unsubscribeKline(networkType, sym, tf, chatId, strategyType); } catch (Exception ignored) {}
                try { ws.unsubscribeAggTrade(networkType, sym, tf, chatId, strategyType); } catch (Exception ignored) {}
            }
        }

        if (subs.isEmpty()) {
            activeSubscriptions.remove(chatId, subs);
        }

        log.info("📴 [STREAM] UNSUBSCRIBE: chatId={} type={} ex={} net={} {} {}",
                chatId, strategyType, ex, networkType, sym, tf);
    }

    public MarketPushResult pushAggTrade(String exchange,
                                         NetworkType networkType,
                                         long chatId,
                                         StrategyType strategyType,
                                         String symbol,
                                         String timeframe,
                                         BigDecimal price,
                                         BigDecimal qty,
                                         long tradeTsMs) {

        long n = seq.incrementAndGet();
        return new MarketPushResult(n, true, false, false, null);
    }

    public void pushKline(String exchange,
                          NetworkType networkType,
                          long chatId,
                          StrategyType strategyType,
                          String symbol,
                          String timeframe,
                          UnifiedKline kline) {

        if (kline == null || strategyType == null) return;

        String ex  = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        if (ex == null || networkType == null || sym == null || tf == null) return;

        try {
            if (kline.getSymbol() == null || kline.getSymbol().isBlank()) kline.setSymbol(sym);
            if (kline.getTimeframe() == null || kline.getTimeframe().isBlank()) kline.setTimeframe(tf);
        } catch (Exception ignored) {}

        Candle candle = toCandleSafe(kline);
        if (candle == null) return;

        onCandle(chatId, strategyType, ex, networkType, sym, tf, candle);
    }

    public void pushKline(String exchange,
                          NetworkType networkType,
                          long chatId,
                          StrategyType strategyType,
                          UnifiedKline kline) {

        if (kline == null || strategyType == null) return;

        String ex = normExchange(exchange);
        if (ex == null || networkType == null) return;

        String symRaw = readStringAny(kline, "getSymbol", "symbol", "getS", "s").orElse(null);
        String tfRaw  = readStringAny(kline, "getTimeframe", "timeframe", "getInterval", "interval").orElse(null);

        String sym = normSymbol(symRaw);
        String tf  = normTf(tfRaw);

        if (sym == null || tf == null) return;

        try {
            if (kline.getSymbol() == null || kline.getSymbol().isBlank()) kline.setSymbol(sym);
            if (kline.getTimeframe() == null || kline.getTimeframe().isBlank()) kline.setTimeframe(tf);
        } catch (Exception ignored) {}

        Candle candle = toCandleSafe(kline);
        if (candle == null) return;

        onCandle(chatId, strategyType, ex, networkType, sym, tf, candle);
    }

    public record MarketPushResult(
            long seq,
            boolean pushedTick,
            boolean pushedCandle,
            boolean createdCandle,
            UnifiedKline candleClosed
    ) {}

    // =====================================================================
    // 🕯 + 🔥 Подписка на свечи и live ticks
    // =====================================================================

    public void subscribeCandles(String exchange,
                                 NetworkType networkType,
                                 long chatId,
                                 StrategyType strategyType,
                                 String symbol,
                                 String timeframe) {

        String ex  = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        if (ex == null || networkType == null || strategyType == null || sym == null || tf == null) {
            log.warn("⚠️ [STREAM] subscribeCandles пропуск: chatId={} type={} ex={} net={} symbol={} tf={}",
                    chatId, strategyType, exchange, networkType, symbol, timeframe);
            return;
        }

        dropOtherSubscriptionsSameType(chatId, strategyType, ex, networkType, sym, tf);

        candleStorage.computeIfAbsent(
                new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf),
                __ -> new ConcurrentLinkedDeque<>()
        );

        Set<SubscriptionKey> subs =
                activeSubscriptions.computeIfAbsent(chatId, __ -> ConcurrentHashMap.newKeySet());

        SubscriptionKey key = new SubscriptionKey(strategyType, ex, networkType, sym, tf);

        if (!subs.add(key)) {
            log.debug("⏭ [STREAM] Уже подписаны: chatId={} type={} ex={} net={} {} {}",
                    chatId, strategyType, ex, networkType, sym, tf);
            return;
        }

        if (!"BINANCE".equalsIgnoreCase(ex)) {
            subs.remove(key);
            candleStorage.remove(new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf));
            log.warn("⚠️ [STREAM] subscribeCandles: биржа '{}' пока не подключена для WS", ex);
            return;
        }

        BinanceSpotWebSocketClient ws = binanceWsProvider.getIfAvailable();
        if (ws == null) {
            subs.remove(key);
            candleStorage.remove(new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf));
            log.error("❌ [STREAM] BINANCE ws client отсутствует (bean not available) chatId={} type={}", chatId, strategyType);
            return;
        }

        try {
            ws.subscribeKline(networkType, sym, tf, chatId, strategyType);
            ws.subscribeAggTrade(networkType, sym, tf, chatId, strategyType);

            log.info("📡 [STREAM] SUBSCRIBE WS: chatId={} type={} ex={} net={} {} {} (KLINE+AGGTRADE)",
                    chatId, strategyType, ex, networkType, sym, tf);
        } catch (Exception e) {
            subs.remove(key);
            candleStorage.remove(new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf));

            log.error("❌ [STREAM] SUBSCRIBE FAILED chatId={} type={} ex={} net={} {} {} err={}",
                    chatId, strategyType, ex, networkType, sym, tf, e.getMessage(), e);
        }
    }

    private void dropOtherSubscriptionsSameType(long chatId,
                                                StrategyType type,
                                                String ex,
                                                NetworkType net,
                                                String sym,
                                                String tf) {

        Set<SubscriptionKey> subs = activeSubscriptions.get(chatId);
        if (subs == null || subs.isEmpty()) return;

        SubscriptionKey keep = new SubscriptionKey(type, ex, net, sym, tf);

        for (SubscriptionKey k : List.copyOf(subs)) {
            if (k == null) continue;
            if (k.strategyType() != type) continue;
            if (k.equals(keep)) continue;

            try {
                unsubscribe(k.exchange(), k.networkType(), chatId, k.strategyType(), k.symbol(), k.timeframe());
            } catch (Exception ignored) {}
        }
    }

    // =====================================================================
    // 🕯 CALLBACK ДЛЯ LIVE СВЕЧЕЙ
    // =====================================================================

    public void onCandle(long chatId,
                         StrategyType strategyType,
                         String exchange,
                         NetworkType networkType,
                         String symbol,
                         String timeframe,
                         Candle candle) {

        String ex  = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        if (ex == null || networkType == null || strategyType == null || sym == null || tf == null || candle == null) return;

        CandleStoreKey key = new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf);
        Deque<Candle> deque = candleStorage.computeIfAbsent(key, __ -> new ConcurrentLinkedDeque<>());

        synchronized (deque) {
            Candle last = deque.peekLast();

            if (last != null && last.getTime() == candle.getTime()) {
                deque.pollLast();
            }

            deque.addLast(candle);

            while (deque.size() > MAX_CANDLES) {
                deque.pollFirst();
            }
        }

        pushToStreamManager(ex, networkType, sym, tf, candle);

        if (log.isDebugEnabled()) {
            log.debug("🕯 [STREAM] CANDLE IN chatId={} type={} ex={} net={} {} {} time={}",
                    chatId, strategyType, ex, networkType, sym, tf, candle.getTime());
        }
    }

    // =====================================================================
    // ✅ ПУБЛИЧНЫЙ API ДЛЯ БЭКТЕСТА/ТЮНЕРА (КЭШ С LIMIT)
    // =====================================================================

    /**
     * ✅ Сигнатура, которую ищет MarketStreamBacktestCandlePort в первую очередь.
     */
    public List<Candle> getCachedCandles(long chatId,
                                         StrategyType strategyType,
                                         String exchange,
                                         NetworkType networkType,
                                         String symbol,
                                         String timeframe,
                                         int limit) {

        String ex  = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        if (chatId <= 0 || strategyType == null || ex == null || networkType == null || sym == null || tf == null) {
            return List.of();
        }

        Deque<Candle> deque = candleStorage.get(new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf));
        if (deque != null && !deque.isEmpty()) {
            return copyTail(deque, limit);
        }

        // ✅ fallback: общий streamManager (там warmup пишет свечи тоже)
        if (streamManager != null) {
            return streamManager.getCandles(ex, networkType, sym, tf, limit);
        }

        return List.of();
    }

    /**
     * ✅ Backward: без env — пробуем найти “лучший” deque по chatId/type/sym/tf,
     * иначе падаем в streamManager legacy.
     */
    public List<Candle> getCachedCandles(long chatId,
                                         StrategyType strategyType,
                                         String symbol,
                                         String timeframe,
                                         int limit) {

        String sym = normSymbol(symbol);
        String tf = normTf(timeframe);

        if (chatId <= 0 || strategyType == null || sym == null || tf == null) return List.of();

        Deque<Candle> best = null;
        int bestSize = 0;

        for (Map.Entry<CandleStoreKey, Deque<Candle>> e : candleStorage.entrySet()) {
            CandleStoreKey k = e.getKey();
            if (k == null) continue;
            if (k.chatId != chatId) continue;
            if (k.strategyType != strategyType) continue;
            if (!sym.equals(k.symbol)) continue;
            if (!tf.equals(k.timeframe)) continue;

            Deque<Candle> d = e.getValue();
            if (d == null) continue;

            int size = d.size();
            if (size > bestSize) {
                bestSize = size;
                best = d;
            }
        }

        if (best != null && !best.isEmpty()) {
            return copyTail(best, limit);
        }

        if (streamManager != null) {
            return streamManager.getCandles(sym, tf, limit);
        }

        return List.of();
    }

    /**
     * ✅ Удобный алиас (некоторые места ищут getCandles(..., limit)).
     */
    public List<Candle> getCandles(long chatId,
                                   StrategyType strategyType,
                                   String exchange,
                                   NetworkType networkType,
                                   String symbol,
                                   String timeframe,
                                   int limit) {
        return getCachedCandles(chatId, strategyType, exchange, networkType, symbol, timeframe, limit);
    }

    // =====================================================================
    // (старый) getCandles без limit — оставляем
    // =====================================================================

    public List<Candle> getCandles(long chatId,
                                   StrategyType strategyType,
                                   String exchange,
                                   NetworkType networkType,
                                   String symbol,
                                   String timeframe) {

        String ex  = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        if (ex == null || networkType == null || strategyType == null || sym == null || tf == null) return List.of();

        Deque<Candle> deque = candleStorage.get(new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf));
        if (deque == null || deque.isEmpty()) return List.of();

        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    public void putCandles(long chatId,
                           StrategyType strategyType,
                           String exchange,
                           NetworkType networkType,
                           String symbol,
                           String timeframe,
                           List<Candle> candles) {

        String ex  = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        if (ex == null || networkType == null || strategyType == null || sym == null || tf == null) return;

        CandleStoreKey key = new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf);
        Deque<Candle> deque = candleStorage.computeIfAbsent(key, __ -> new ConcurrentLinkedDeque<>());

        synchronized (deque) {
            deque.clear();
            if (candles != null && !candles.isEmpty()) {
                for (Candle c : candles) {
                    if (c == null) continue;
                    deque.addLast(c);
                    while (deque.size() > MAX_CANDLES) deque.pollFirst();
                }
            }
        }

        // ✅ важно: если мы прогрели историю — положим также в streamManager
        if (candles != null && !candles.isEmpty()) {
            for (Candle c : candles) {
                if (c == null) continue;
                pushToStreamManager(ex, networkType, sym, tf, c);
            }
        }

        log.info("📦 [STREAM] Cache initialized: {} candles для chatId={} type={} ex={} net={} {} {}",
                deque.size(), chatId, strategyType, ex, networkType, sym, tf);
    }

    public synchronized void unsubscribeAll(long chatId) {

        Set<SubscriptionKey> subs = activeSubscriptions.get(chatId);
        if (subs == null || subs.isEmpty()) {
            candleStorage.keySet().removeIf(k -> k.chatId() == chatId);
            return;
        }

        for (SubscriptionKey key : List.copyOf(subs)) {
            try {
                unsubscribe(key.exchange(), key.networkType(), chatId, key.strategyType(), key.symbol(), key.timeframe());
            } catch (Exception ignored) {}
        }

        activeSubscriptions.remove(chatId);
        candleStorage.keySet().removeIf(k -> k.chatId() == chatId);

        log.info("🧹 [STREAM] UNSUBSCRIBE ALL for chatId={}", chatId);
    }

    // =====================================================================
    // internals: copy tail
    // =====================================================================

    private List<Candle> copyTail(Deque<Candle> deque, int limit) {
        if (deque == null || deque.isEmpty()) return List.of();

        int lim = Math.max(1, limit);

        synchronized (deque) {
            int size = deque.size();
            if (size <= lim) return new ArrayList<>(deque);

            ArrayList<Candle> out = new ArrayList<>(lim);
            Iterator<Candle> it = deque.descendingIterator();
            while (it.hasNext() && out.size() < lim) out.add(it.next());
            Collections.reverse(out);
            return out;
        }
    }

    // =====================================================================
    // MarketStreamManager compat (reflection, чтобы не ломать сборку)
    // =====================================================================

    private void pushToStreamManager(String exchange, NetworkType network, String symbol, String timeframe, Candle candle) {
        if (streamManager == null) return;

        try {
            Method m5 = findMethod(streamManager.getClass(), "addCandle", 5);
            if (m5 != null) {
                m5.invoke(streamManager, exchange, network, symbol, timeframe, candle);
                return;
            }

            Method m3 = findMethod(streamManager.getClass(), "addCandle", 3);
            if (m3 != null) {
                m3.invoke(streamManager, symbol, timeframe, candle);
            }
        } catch (Exception ignored) {
        }
    }

    private Method findMethod(Class<?> cls, String name, int paramCount) {
        for (Method m : cls.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getParameterCount() != paramCount) continue;
            return m;
        }
        return null;
    }

    // =====================================================================
    // keys + нормализация
    // =====================================================================

    private record SubscriptionKey(StrategyType strategyType,
                                   String exchange,
                                   NetworkType networkType,
                                   String symbol,
                                   String timeframe) {}

    private record CandleStoreKey(long chatId,
                                  StrategyType strategyType,
                                  String exchange,
                                  NetworkType networkType,
                                  String symbol,
                                  String timeframe) {}

    private static String normSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normTf(String timeframe) {
        if (timeframe == null) return null;
        String s = timeframe.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normExchange(String exchange) {
        if (exchange == null) return null;
        String s = exchange.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    // =====================================================================
    // ✅ UnifiedKline -> Candle (safe, без падений)
    // =====================================================================

    private Candle toCandleSafe(UnifiedKline kline) {
        long openTime = readLongAny(kline, "getOpenTime", "openTime", "getT", "t").orElse(0L);

        Double open  = readDoubleAny(kline, "getOpen", "open").orElse(null);
        Double high  = readDoubleAny(kline, "getHigh", "high").orElse(null);
        Double low   = readDoubleAny(kline, "getLow", "low").orElse(null);
        Double close = readDoubleAny(kline, "getClose", "close").orElse(null);
        Double vol   = readDoubleAny(kline, "getVolume", "volume").orElse(0.0);

        boolean closed = readBoolAny(kline, "isClosed", "getClosed", "closed", "isFinal", "getFinal", "final")
                .orElse(false);

        if (openTime <= 0 || open == null || high == null || low == null || close == null) {
            return null;
        }

        return new Candle(openTime, open, high, low, close, vol, closed);
    }

    // =====================================================================
    // reflection helpers (метод ИЛИ поле)
    // =====================================================================

    private static Optional<Object> readAny(Object target, String... names) {
        if (target == null || names == null) return Optional.empty();

        Class<?> c = target.getClass();

        for (String n : names) {
            if (n == null || n.isBlank()) continue;

            Method m = findNoArgMethod(c, n);
            if (m != null) {
                try {
                    return Optional.ofNullable(m.invoke(target));
                } catch (Exception ignored) {}
            }

            Field f = findField(c, n);
            if (f != null) {
                try {
                    f.setAccessible(true);
                    return Optional.ofNullable(f.get(target));
                } catch (Exception ignored) {}
            }
        }

        return Optional.empty();
    }

    private static Optional<String> readStringAny(Object target, String... names) {
        Object v = readAny(target, names).orElse(null);
        if (v == null) return Optional.empty();
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? Optional.empty() : Optional.of(s);
    }

    private static Optional<Long> readLongAny(Object target, String... names) {
        Object v = readAny(target, names).orElse(null);
        if (v == null) return Optional.empty();

        if (v instanceof Number n) return Optional.of(n.longValue());

        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return Optional.empty();

        try { return Optional.of(Long.parseLong(s)); }
        catch (Exception ignored) { return Optional.empty(); }
    }

    private static Optional<Boolean> readBoolAny(Object target, String... names) {
        Object v = readAny(target, names).orElse(null);
        if (v == null) return Optional.empty();

        if (v instanceof Boolean b) return Optional.of(b);

        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return Optional.empty();

        return Optional.of(Boolean.parseBoolean(s));
    }

    private static Optional<Double> readDoubleAny(Object target, String... names) {
        Object v = readAny(target, names).orElse(null);
        if (v == null) return Optional.empty();

        if (v instanceof Double d) return Optional.of(d);
        if (v instanceof Float f) return Optional.of((double) f);
        if (v instanceof Integer i) return Optional.of((double) i);
        if (v instanceof Long l) return Optional.of((double) l);
        if (v instanceof BigDecimal bd) return Optional.of(bd.doubleValue());
        if (v instanceof Number n) return Optional.of(n.doubleValue());

        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return Optional.empty();

        try { return Optional.of(Double.parseDouble(s)); }
        catch (Exception ignored) { return Optional.empty(); }
    }

    private static Method findNoArgMethod(Class<?> c, String name) {
        try { return c.getMethod(name); } catch (Exception ignored) {}

        String cap = (name.length() > 0)
                ? Character.toUpperCase(name.charAt(0)) + name.substring(1)
                : name;

        try { return c.getMethod("get" + cap); } catch (Exception ignored) {}
        try { return c.getMethod("is" + cap); } catch (Exception ignored) {}

        return null;
    }

    private static Field findField(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            try {
                return cur.getDeclaredField(name);
            } catch (Exception ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }
}