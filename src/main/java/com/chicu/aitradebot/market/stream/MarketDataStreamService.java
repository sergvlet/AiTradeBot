package com.chicu.aitradebot.market.stream;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.binance.ws.BinanceSpotWebSocketClient;
import com.chicu.aitradebot.market.MarketStreamManager;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.market.model.UnifiedKline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

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

    /** ✅ общий кэш свечей для бэктеста/дашборда */
    private final MarketStreamManager streamManager;

    /** ✅ публикуем события (если оркестратор слушает @EventListener) */
    private final ApplicationEventPublisher eventPublisher;

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
                                   MarketStreamManager streamManager,
                                   ApplicationEventPublisher eventPublisher) {
        this.binanceWsProvider = binanceWsProvider;
        this.streamManager = streamManager;
        this.eventPublisher = eventPublisher;
    }

    // =====================================================================
    // ✅ EVENTS (должны быть public, т.к. их читает другой пакет)
    // =====================================================================

    public static record MarketTickEvent(
            String exchange,
            NetworkType networkType,
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            BigDecimal price,
            BigDecimal qty,
            long tsMs
    ) {}

    public static record CandleClosedEvent(
            String exchange,
            NetworkType networkType,
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            UnifiedKline kline
    ) {}

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
                try { ws.unsubscribeBookTicker(networkType, sym, chatId, strategyType); } catch (Exception ignored) {}
            }
        }

        if (subs.isEmpty()) {
            activeSubscriptions.remove(chatId, subs);
        }

        log.info("📴 [STREAM] UNSUBSCRIBE: chatId={} type={} ex={} net={} {} {}",
                chatId, strategyType, ex, networkType, sym, tf);
    }

    /**
     * ✅ Реальный tick:
     * - строим synthetic candle по timeframe (из тиков)
     * - обновляем candleStorage + streamManager
     * - при смене бакета возвращаем candleClosed (UnifiedKline)
     */
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

        String ex  = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        if (ex == null || networkType == null || chatId <= 0 || strategyType == null || sym == null) {
            return new MarketPushResult(n, false, false, false, null);
        }

        if (price == null || price.signum() <= 0 || tradeTsMs <= 0) {
            return new MarketPushResult(n, false, false, false, null);
        }

        // ✅ публикуем тик-событие (если оркестратор слушает)
        publishSafe(new MarketTickEvent(ex, networkType, chatId, strategyType, sym, tf, price, qty, tradeTsMs));

        boolean pushedCandle = false;
        boolean createdCandle = false;
        UnifiedKline candleClosed = null;

        long tfMs = parseTimeframeMs(tf);
        if (tfMs > 0) {
            long openTime = (tradeTsMs / tfMs) * tfMs;

            double p = safeDouble(price);
            double v = (qty != null ? Math.max(0.0, safeDouble(qty)) : 0.0);

            CandleStoreKey key = new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf);
            Deque<Candle> deque = candleStorage.computeIfAbsent(key, __ -> new ConcurrentLinkedDeque<>());

            Candle lastNow;

            synchronized (deque) {
                Candle last = deque.peekLast();

                if (last == null) {
                    Candle c = new Candle(openTime, p, p, p, p, v, false);
                    deque.addLast(c);
                    createdCandle = true;
                    pushedCandle = true;

                } else if (last.getTime() == openTime) {
                    // update forming candle
                    double open = last.getOpen();
                    double high = Math.max(last.getHigh(), p);
                    double low  = Math.min(last.getLow(), p);
                    double vol  = last.getVolume() + v;

                    Candle c = new Candle(openTime, open, high, low, p, vol, false);
                    deque.pollLast();
                    deque.addLast(c);
                    pushedCandle = true;

                } else if (last.getTime() < openTime) {
                    // закрываем предыдущий бакет
                    Candle prevClosed = new Candle(
                            last.getTime(),
                            last.getOpen(),
                            last.getHigh(),
                            last.getLow(),
                            last.getClose(),
                            last.getVolume(),
                            true
                    );
                    deque.pollLast();
                    deque.addLast(prevClosed);

                    candleClosed = UnifiedKline.builder()
                            .openTime(prevClosed.getTime())
                            .closeTime(prevClosed.getTime() + tfMs - 1)
                            .open(BigDecimal.valueOf(prevClosed.getOpen()))
                            .high(BigDecimal.valueOf(prevClosed.getHigh()))
                            .low(BigDecimal.valueOf(prevClosed.getLow()))
                            .close(BigDecimal.valueOf(prevClosed.getClose()))
                            .volume(BigDecimal.valueOf(prevClosed.getVolume()))
                            .timeframe(tf)
                            .symbol(sym)
                            .closed(true)
                            .build();

                    // ✅ публикуем закрытие свечи
                    publishSafe(new CandleClosedEvent(ex, networkType, chatId, strategyType, sym, tf, candleClosed));

                    // новый forming бакет
                    Candle c = new Candle(openTime, p, p, p, p, v, false);
                    deque.addLast(c);
                    createdCandle = true;
                    pushedCandle = true;

                    while (deque.size() > MAX_CANDLES) deque.pollFirst();
                }

                while (deque.size() > MAX_CANDLES) deque.pollFirst();
                lastNow = deque.peekLast();
            }

            if (lastNow != null) {
                pushToStreamManager(ex, networkType, sym, tf, lastNow);
            }
        }

        return new MarketPushResult(n, true, pushedCandle, createdCandle, candleClosed);
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

        if (kline.getSymbol() == null || kline.getSymbol().isBlank()) kline.setSymbol(sym);
        if (kline.getTimeframe() == null || kline.getTimeframe().isBlank()) kline.setTimeframe(tf);

        // ✅ если это закрытая свеча — публикуем событие закрытия
        if (kline.isClosed()) {
            publishSafe(new CandleClosedEvent(ex, networkType, chatId, strategyType, sym, tf, kline));
        }

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

        String sym = normSymbol(kline.getSymbol());
        String tf  = normTf(kline.getTimeframe());

        if (sym == null || tf == null) return;

        pushKline(ex, networkType, chatId, strategyType, sym, tf, kline);
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
            ws.subscribeBookTicker(networkType, sym, chatId, strategyType);

            log.info("📡 [STREAM] SUBSCRIBE WS: chatId={} type={} ex={} net={} {} {} (KLINE+AGGTRADE+BOOK_TICKER)",
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

        if (streamManager != null) {
            return streamManager.getCandles(ex, networkType, sym, tf, limit);
        }

        return List.of();
    }

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

    public List<Candle> getCandles(long chatId,
                                   StrategyType strategyType,
                                   String exchange,
                                   NetworkType networkType,
                                   String symbol,
                                   String timeframe,
                                   int limit) {
        return getCachedCandles(chatId, strategyType, exchange, networkType, symbol, timeframe, limit);
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
    // MarketStreamManager (без reflection)
    // =====================================================================

    private void pushToStreamManager(String exchange, NetworkType network, String symbol, String timeframe, Candle candle) {
        if (streamManager == null || candle == null) return;
        try {
            streamManager.addCandle(exchange, network, symbol, timeframe, candle);
        } catch (Exception ignored) {}
    }

    // =====================================================================
    // events: safe publish
    // =====================================================================

    private void publishSafe(Object event) {
        if (eventPublisher == null || event == null) return;
        try {
            eventPublisher.publishEvent(event);
        } catch (Exception ignored) {}
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
    // ✅ timeframe parser
    // =====================================================================

    private static long parseTimeframeMs(String tf) {
        if (tf == null) return -1;
        String s = tf.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || "na".equals(s)) return -1;

        long mult;
        char unit = s.charAt(s.length() - 1);

        String numStr = s.substring(0, s.length() - 1).trim();
        if (numStr.isEmpty()) return -1;

        long n;
        try { n = Long.parseLong(numStr); }
        catch (Exception e) { return -1; }

        if (n <= 0) return -1;

        if (unit == 's') mult = 1000L;
        else if (unit == 'm') mult = 60_000L;
        else if (unit == 'h') mult = 3_600_000L;
        else if (unit == 'd') mult = 86_400_000L;
        else return -1;

        long ms = n * mult;
        return ms > 0 ? ms : -1;
    }

    private static double safeDouble(BigDecimal v) {
        try { return v.doubleValue(); } catch (Exception e) { return 0.0; }
    }

    // =====================================================================
    // ✅ UnifiedKline -> Candle (safe)
    // =====================================================================

    private Candle toCandleSafe(UnifiedKline kline) {
        if (kline == null) return null;

        long openTime = kline.getOpenTime();
        BigDecimal o = kline.getOpen();
        BigDecimal h = kline.getHigh();
        BigDecimal l = kline.getLow();
        BigDecimal c = kline.getClose();
        BigDecimal v = kline.getVolume();

        if (openTime <= 0 || o == null || h == null || l == null || c == null) {
            return null;
        }

        boolean closed = kline.isClosed();

        double od = safeDouble(o);
        double hd = safeDouble(h);
        double ld = safeDouble(l);
        double cd = safeDouble(c);
        double vd = (v != null ? safeDouble(v) : 0.0);

        return new Candle(openTime, od, hd, ld, cd, vd, closed);
    }
}