package com.chicu.aitradebot.market.stream;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.binance.ws.BinanceSpotWebSocketClient;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.market.model.UnifiedKline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class MarketDataStreamService {

    private static final int MAX_CANDLES = 2_000;

    /**
     * ✅ цикл разорван — берём лениво
     */
    private final ObjectProvider<BinanceSpotWebSocketClient> binanceWsProvider;

    /**
     * seq для логов/троттлинга
     */
    private final AtomicLong seq = new AtomicLong(0);

    /**
     * ✅ Хранилище свечей строго по ключу (никаких смешений):
     * (chatId, type, ex, net, symbol, tf)
     */
    private final ConcurrentMap<CandleStoreKey, CopyOnWriteArrayList<Candle>> candleStorage = new ConcurrentHashMap<>();

    /**
     * chatId → set of подписок (строго с ex+net+sym+tf)
     */
    private final ConcurrentMap<Long, Set<SubscriptionKey>> activeSubscriptions = new ConcurrentHashMap<>();

    public MarketDataStreamService(ObjectProvider<BinanceSpotWebSocketClient> binanceWsProvider) {
        this.binanceWsProvider = binanceWsProvider;
    }

    // =====================================================================
    // ✅ АДАПТЕРЫ ПОД MarketStreamServiceImpl
    // =====================================================================

    public synchronized void subscribe(
            String exchange,
            NetworkType networkType,
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe
    ) {
        subscribeCandles(exchange, networkType, chatId, strategyType, symbol, timeframe);
    }

    public MarketPushResult pushAggTrade(
            String exchange,
            NetworkType networkType,
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            BigDecimal price,
            BigDecimal qty,
            long tradeTsMs
    ) {
        long n = seq.incrementAndGet();

        // Сейчас свечи берём из kline потока, поэтому тут только ack для логики/троттлинга.
        return new MarketPushResult(
                n,
                true,   // pushedTick
                false,  // pushedCandle
                false,  // createdCandle
                null    // candleClosed
        );
    }

    /**
     * ✅ СТРОГИЙ ПУТЬ: symbol + timeframe уже известны из контекста стрима.
     * НИКАКОЙ "угадайки" из kline.
     */
    public void pushKline(
            String exchange,
            NetworkType networkType,
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            UnifiedKline kline
    ) {
        if (kline == null || strategyType == null) return;

        String ex  = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        if (ex == null || networkType == null || sym == null || tf == null) return;

        // ✅ чтобы downstream (UI/логика) всегда видел sym/tf
        try {
            if (kline.getSymbol() == null || kline.getSymbol().isBlank()) kline.setSymbol(sym);
            if (kline.getTimeframe() == null || kline.getTimeframe().isBlank()) kline.setTimeframe(tf);
        } catch (Exception ignored) {}

        Candle candle = toCandleSafe(kline);
        if (candle == null) return;

        onCandle(chatId, strategyType, ex, networkType, sym, tf, candle);
    }

    /**
     * BACKWARD-COMPAT: старый вызов, если кто-то ещё дергает без symbol/timeframe.
     * Теперь корректно пытается вытащить sym/tf и из геттера, и из поля.
     */
    public void pushKline(
            String exchange,
            NetworkType networkType,
            long chatId,
            StrategyType strategyType,
            UnifiedKline kline
    ) {
        if (kline == null || strategyType == null) return;

        String ex = normExchange(exchange);
        if (ex == null || networkType == null) return;

        String symRaw = readStringAny(kline, "getSymbol", "symbol", "getS", "s").orElse(null);
        String tfRaw  = readStringAny(kline, "getTimeframe", "timeframe", "getInterval", "interval").orElse(null);

        String sym = normSymbol(symRaw);
        String tf  = normTf(tfRaw);

        if (sym == null || tf == null) return;

        // ✅ проставим для консистентности
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

    public synchronized void subscribeCandles(
            String exchange,
            NetworkType networkType,
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe
    ) {
        String ex  = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        if (ex == null || networkType == null || strategyType == null || sym == null || tf == null) {
            log.warn("⚠️ [STREAM] subscribeCandles пропуск: chatId={} type={} ex={} net={} symbol={} tf={}",
                    chatId, strategyType, exchange, networkType, symbol, timeframe);
            return;
        }

        // ✅ Если для этого chatId+type уже есть другая подписка — отписываем, чтобы не было каши.
        dropOtherSubscriptionsSameType(chatId, strategyType, ex, networkType, sym, tf);

        candleStorage.computeIfAbsent(
                new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf),
                __ -> new CopyOnWriteArrayList<>()
        );

        Set<SubscriptionKey> subs =
                activeSubscriptions.computeIfAbsent(chatId, __ -> ConcurrentHashMap.newKeySet());

        SubscriptionKey key = new SubscriptionKey(strategyType, ex, networkType, sym, tf);

        if (subs.contains(key)) {
            log.debug("⏭ [STREAM] Уже подписаны: chatId={} type={} ex={} net={} {} {}",
                    chatId, strategyType, ex, networkType, sym, tf);
            return;
        }

        if (!"BINANCE".equalsIgnoreCase(ex)) {
            log.warn("⚠️ [STREAM] subscribeCandles: биржа '{}' пока не подключена для WS в MarketDataStreamService", ex);
            return;
        }

        BinanceSpotWebSocketClient ws = binanceWsProvider.getObject();

        ws.subscribeKline(networkType, sym, tf, chatId, strategyType);
        ws.subscribeAggTrade(networkType, sym, tf, chatId, strategyType);

        subs.add(key);

        log.info("📡 [STREAM] SUBSCRIBE WS: chatId={} type={} ex={} net={} {} {} (KLINE+AGGTRADE)",
                chatId, strategyType, ex, networkType, sym, tf);
    }

    private void dropOtherSubscriptionsSameType(
            long chatId,
            StrategyType type,
            String ex,
            NetworkType net,
            String sym,
            String tf
    ) {
        Set<SubscriptionKey> subs = activeSubscriptions.computeIfAbsent(chatId, __ -> ConcurrentHashMap.newKeySet());
        if (subs.isEmpty()) return;

        SubscriptionKey keep = new SubscriptionKey(type, ex, net, sym, tf);

        BinanceSpotWebSocketClient ws = binanceWsProvider.getObject();

        for (SubscriptionKey k : List.copyOf(subs)) {
            if (k == null) continue;
            if (k.strategyType() != type) continue;
            if (k.equals(keep)) continue;

            if ("BINANCE".equalsIgnoreCase(k.exchange())) {
                try {
                    ws.unsubscribeKline(k.networkType(), k.symbol(), k.timeframe(), chatId, k.strategyType());
                } catch (Exception ignored) {}
                try {
                    ws.unsubscribeAggTrade(k.networkType(), k.symbol(), k.timeframe(), chatId, k.strategyType());
                } catch (Exception ignored) {}
            }

            subs.remove(k);

            // и кэш свечей по этому ключу можно убрать
            candleStorage.keySet().removeIf(storeKey ->
                    storeKey.chatId() == chatId
                    && storeKey.strategyType() == type
                    && eq(storeKey.exchange(), k.exchange())
                    && storeKey.networkType() == k.networkType()
                    && eq(storeKey.symbol(), k.symbol())
                    && eq(storeKey.timeframe(), k.timeframe())
            );

            log.info("🧹 [STREAM] drop old subscription: chatId={} type={} ex={} net={} {} {}",
                    chatId, type, k.exchange(), k.networkType(), k.symbol(), k.timeframe());
        }
    }

    // =====================================================================
    // 🕯 CALLBACK ДЛЯ LIVE СВЕЧЕЙ
    // =====================================================================

    public void onCandle(
            long chatId,
            StrategyType strategyType,
            String exchange,
            NetworkType networkType,
            String symbol,
            String timeframe,
            Candle candle
    ) {
        String ex  = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        if (ex == null || networkType == null || strategyType == null || sym == null || tf == null || candle == null) return;

        CandleStoreKey key = new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf);
        CopyOnWriteArrayList<Candle> candles = candleStorage.computeIfAbsent(key, __ -> new CopyOnWriteArrayList<>());

        candles.add(candle);
        if (candles.size() > MAX_CANDLES) candles.remove(0);

        if (log.isDebugEnabled()) {
            log.debug("🕯 [STREAM] CANDLE IN chatId={} type={} ex={} net={} {} {} time={}",
                    chatId, strategyType, ex, networkType, sym, tf, candle.getTime());
        }
    }

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

        return candleStorage.getOrDefault(
                new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf),
                new CopyOnWriteArrayList<>()
        );
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
        CopyOnWriteArrayList<Candle> target = candleStorage.computeIfAbsent(key, __ -> new CopyOnWriteArrayList<>());

        target.clear();
        if (candles != null && !candles.isEmpty()) target.addAll(candles);

        log.info("📦 [STREAM] Cache initialized: {} candles для chatId={} type={} ex={} net={} {} {}",
                target.size(), chatId, strategyType, ex, networkType, sym, tf);
    }

    public synchronized void unsubscribeAll(long chatId) {

        Set<SubscriptionKey> subs = activeSubscriptions.remove(chatId);
        if (subs == null || subs.isEmpty()) return;

        BinanceSpotWebSocketClient ws = binanceWsProvider.getObject();

        for (SubscriptionKey key : subs) {
            if (!"BINANCE".equalsIgnoreCase(key.exchange())) continue;

            ws.unsubscribeKline(key.networkType(), key.symbol(), key.timeframe(), chatId, key.strategyType());
            ws.unsubscribeAggTrade(key.networkType(), key.symbol(), key.timeframe(), chatId, key.strategyType());
        }

        // ✅ удаляем ВСЕ свечи этого chatId
        candleStorage.keySet().removeIf(k -> k.chatId() == chatId);

        log.info("🧹 [STREAM] UNSUBSCRIBE ALL for chatId={}", chatId);
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

    private static boolean eq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
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

            // 1) метод без аргументов
            Method m = findNoArgMethod(c, n);
            if (m != null) {
                try {
                    return Optional.ofNullable(m.invoke(target));
                } catch (Exception ignored) {}
            }

            // 2) поле
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
