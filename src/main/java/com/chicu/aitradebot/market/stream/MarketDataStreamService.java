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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
     *
     * ⚡️ ВАЖНО: Deque быстрее/дешевле чем CopyOnWriteArrayList при частых апдейтах.
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

    /** Подписка на нужный поток. Внутри — строгий dedupe по chatId+type+ex+net+sym+tf. */
    public void subscribe(String exchange,
                          NetworkType networkType,
                          long chatId,
                          StrategyType strategyType,
                          String symbol,
                          String timeframe) {
        subscribeCandles(exchange, networkType, chatId, strategyType, symbol, timeframe);
    }

    /** ✅ Явная отписка (нужна для корректного рестарта). */
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

        // ✅ сначала убираем из set — чтобы параллельный subscribe видел правду
        boolean removed = subs.remove(key);
        if (!removed) return;

        // ✅ чистим локальный кэш свечей
        candleStorage.remove(new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf));

        // ✅ закрываем WS
        if ("BINANCE".equalsIgnoreCase(ex)) {
            BinanceSpotWebSocketClient ws = binanceWsProvider.getIfAvailable();
            if (ws != null) {
                try { ws.unsubscribeKline(networkType, sym, tf, chatId, strategyType); } catch (Exception ignored) {}
                // AggTrade не зависит от tf, но сигнатура оставлена совместимой (timeframeIgnored)
                try { ws.unsubscribeAggTrade(networkType, sym, tf, chatId, strategyType); } catch (Exception ignored) {}
            }
        }

        // ✅ подчистим пустой set, чтобы map не рос
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

        // Сейчас свечи берём из kline потока, поэтому тут только ack для логики/троттлинга.
        return new MarketPushResult(
                n,
                true,   // pushedTick
                false,  // pushedCandle
                false,  // createdCandle
                null    // candleClosed
        );
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

    /**
     * ✅ ВАЖНО:
     * - гарантируем dedupe через subs.add(key) ДО subscribe
     * - удаляем старые подписки того же type (для этого chatId) перед новой
     */
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

        // 1) сначала убираем другие подписки ЭТОГО type у chatId
        dropOtherSubscriptionsSameType(chatId, strategyType, ex, networkType, sym, tf);

        // 2) создаём хранилище свечей
        candleStorage.computeIfAbsent(
                new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf),
                __ -> new ConcurrentLinkedDeque<>()
        );

        Set<SubscriptionKey> subs =
                activeSubscriptions.computeIfAbsent(chatId, __ -> ConcurrentHashMap.newKeySet());

        SubscriptionKey key = new SubscriptionKey(strategyType, ex, networkType, sym, tf);

        // ✅ дедуп: если уже есть — выходим
        if (!subs.add(key)) {
            log.debug("⏭ [STREAM] Уже подписаны: chatId={} type={} ex={} net={} {} {}",
                    chatId, strategyType, ex, networkType, sym, tf);
            return;
        }

        // 3) подписка WS (если не поддерживаем биржу — откатываем subs.add)
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
            // AggTrade не зависит от tf, но сигнатура совместимая (timeframeIgnored)
            ws.subscribeAggTrade(networkType, sym, tf, chatId, strategyType);

            log.info("📡 [STREAM] SUBSCRIBE WS: chatId={} type={} ex={} net={} {} {} (KLINE+AGGTRADE)",
                    chatId, strategyType, ex, networkType, sym, tf);
        } catch (Exception e) {
            // ❗ если подписка упала — откатываем key/кэш, чтобы можно было попробовать снова
            subs.remove(key);
            candleStorage.remove(new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf));

            log.error("❌ [STREAM] SUBSCRIBE FAILED chatId={} type={} ex={} net={} {} {} err={}",
                    chatId, strategyType, ex, networkType, sym, tf, e.getMessage(), e);
        }
    }

    /**
     * Удаляет все подписки этого chatId по этому type, кроме keep.
     * ⚠️ Не создаём пустые set'ы: используем get() вместо computeIfAbsent().
     */
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

            // ✅ единый путь отписки (уберёт из subs + почистит storage + закроет WS)
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

            // обновление текущей свечи (same openTime)
            if (last != null && last.getTime() == candle.getTime()) {
                deque.pollLast();
            }

            deque.addLast(candle);

            while (deque.size() > MAX_CANDLES) {
                deque.pollFirst();
            }
        }

        // ✅ КРИТИЧНО: складываем также в MarketStreamManager (для бэктеста/дашборда)
        pushToStreamManager(ex, networkType, sym, tf, candle);

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

        log.info("📦 [STREAM] Cache initialized: {} candles для chatId={} type={} ex={} net={} {} {}",
                deque.size(), chatId, strategyType, ex, networkType, sym, tf);
    }

    public synchronized void unsubscribeAll(long chatId) {

        Set<SubscriptionKey> subs = activeSubscriptions.get(chatId);
        if (subs == null || subs.isEmpty()) {
            // на всякий случай подчистим storage
            candleStorage.keySet().removeIf(k -> k.chatId() == chatId);
            return;
        }

        for (SubscriptionKey key : List.copyOf(subs)) {
            try {
                unsubscribe(key.exchange(), key.networkType(), chatId, key.strategyType(), key.symbol(), key.timeframe());
            } catch (Exception ignored) {}
        }

        // финально удаляем пустой set
        activeSubscriptions.remove(chatId);

        // ✅ удаляем ВСЕ свечи этого chatId
        candleStorage.keySet().removeIf(k -> k.chatId() == chatId);

        log.info("🧹 [STREAM] UNSUBSCRIBE ALL for chatId={}", chatId);
    }

    // =====================================================================
    // MarketStreamManager compat (reflection, чтобы не ломать сборку)
    // =====================================================================

    private void pushToStreamManager(String exchange, NetworkType network, String symbol, String timeframe, Candle candle) {
        if (streamManager == null) return;

        try {
            // 1) new signature: addCandle(String ex, NetworkType net, String sym, String tf, Candle c)
            Method m5 = findMethod(streamManager.getClass(), "addCandle", 5);
            if (m5 != null) {
                m5.invoke(streamManager, exchange, network, symbol, timeframe, candle);
                return;
            }

            // 2) old signature: addCandle(String sym, String tf, Candle c)
            Method m3 = findMethod(streamManager.getClass(), "addCandle", 3);
            if (m3 != null) {
                m3.invoke(streamManager, symbol, timeframe, candle);
            }
        } catch (Exception ignored) {
            // не шумим: это best-effort
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