package com.chicu.aitradebot.market;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.market.model.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
@Component
public class MarketStreamManager {

    /**
     * ✅ LEGACY cache (без exchange/network) — для совместимости со старым кодом.
     * SYMBOL → TIMEFRAME → DEQUE<CANDLE>
     */
    private final Map<String, Map<String, Deque<Candle>>> legacyCache = new ConcurrentHashMap<>();

    /**
     * ✅ ENV cache (строго по exchange/network).
     * EXCHANGE → NETWORK → SYMBOL → TIMEFRAME → DEQUE<CANDLE>
     */
    private final Map<String, Map<NetworkType, Map<String, Map<String, Deque<Candle>>>>> envCache = new ConcurrentHashMap<>();

    /** Максимум свечей в памяти (на каждый deque) */
    private volatile int maxCandles = 1500;

    // ============================
    // NORMALIZATION
    // ============================

    private static String normExchangeOrNull(String ex) {
        if (ex == null) return null;
        String s = ex.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normSymbolOrNull(String s) {
        if (s == null) return null;
        String x = s.trim().toUpperCase(Locale.ROOT);

        int idx = x.indexOf("@");
        if (idx > 0) {
            x = x.substring(0, idx);
        }
        return x.isEmpty() ? null : x;
    }

    private static String normTfOrNull(String tf) {
        if (tf == null) return null;
        String x = tf.trim().toLowerCase(Locale.ROOT);

        if (x.startsWith("kline_")) {
            x = x.substring(6);
        }

        // защита от мусора
        while (x.endsWith("_")) {
            x = x.substring(0, x.length() - 1);
        }

        return x.isEmpty() ? null : x;
    }

    // ============================
    // WRITE (LEGACY)
    // ============================

    public void addCandle(String symbol, String timeframe, Candle candle) {
        if (candle == null) return;

        String sym = normSymbolOrNull(symbol);
        String tf = normTfOrNull(timeframe);
        if (sym == null || tf == null) return;

        Map<String, Deque<Candle>> tfMap =
                legacyCache.computeIfAbsent(sym, k -> new ConcurrentHashMap<>());

        Deque<Candle> deque =
                tfMap.computeIfAbsent(tf, k -> new ConcurrentLinkedDeque<>());

        addToDeque(deque, candle);
    }

    // ============================
    // WRITE (ENV)
    // ============================

    public void addCandle(String exchange, NetworkType network, String symbol, String timeframe, Candle candle) {
        if (exchange == null || network == null) {
            // ✅ без дефолтов: если env не передали — это legacy запись
            addCandle(symbol, timeframe, candle);
            return;
        }
        if (candle == null) return;

        String ex = normExchangeOrNull(exchange);
        String sym = normSymbolOrNull(symbol);
        String tf = normTfOrNull(timeframe);
        if (ex == null || sym == null || tf == null) return;

        Map<NetworkType, Map<String, Map<String, Deque<Candle>>>> netMap =
                envCache.computeIfAbsent(ex, k -> new ConcurrentHashMap<>());

        Map<String, Map<String, Deque<Candle>>> symMap =
                netMap.computeIfAbsent(network, k -> new ConcurrentHashMap<>());

        Map<String, Deque<Candle>> tfMap =
                symMap.computeIfAbsent(sym, k -> new ConcurrentHashMap<>());

        Deque<Candle> deque =
                tfMap.computeIfAbsent(tf, k -> new ConcurrentLinkedDeque<>());

        addToDeque(deque, candle);
    }

    private void addToDeque(Deque<Candle> deque, Candle candle) {
        synchronized (deque) {

            Candle last = deque.peekLast();

            // первая свеча
            if (last == null) {
                deque.addLast(candle);
                return;
            }

            // обновление текущей
            if (last.getTime() == candle.getTime()) {
                deque.pollLast();
                deque.addLast(candle);
                return;
            }

            // защита от старых данных
            if (candle.getTime() < last.getTime()) {
                if (log.isDebugEnabled()) {
                    log.debug("⏪ Skip old candle {} < {}", candle.getTime(), last.getTime());
                }
                return;
            }

            // новая свеча
            deque.addLast(candle);

            while (deque.size() > maxCandles) {
                deque.pollFirst();
            }
        }
    }

    // ============================
    // READ (LEGACY)
    // ============================

    public List<Candle> getCandles(String symbol, String timeframe, int limit) {

        String sym = normSymbolOrNull(symbol);
        String tf = normTfOrNull(timeframe);
        if (sym == null || tf == null) return List.of();

        Map<String, Deque<Candle>> tfMap =
                legacyCache.computeIfAbsent(sym, k -> new ConcurrentHashMap<>());

        Deque<Candle> deque =
                tfMap.computeIfAbsent(tf, k -> new ConcurrentLinkedDeque<>());

        if (deque.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("📭 No candles yet (legacy): symbol={} tf={}", sym, tf);
            }
            return List.of();
        }

        return readFromDeque(deque, limit);
    }

    // ============================
    // READ (ENV)
    // ============================

    public List<Candle> getCandles(String exchange, NetworkType network, String symbol, String timeframe, int limit) {

        if (exchange == null || network == null) {
            // ✅ без дефолтов: если env нет — только legacy
            return getCandles(symbol, timeframe, limit);
        }

        String ex = normExchangeOrNull(exchange);
        String sym = normSymbolOrNull(symbol);
        String tf = normTfOrNull(timeframe);
        if (ex == null || sym == null || tf == null) return List.of();

        Deque<Candle> deque = getEnvDeque(ex, network, sym, tf, false);

        if (deque == null || deque.isEmpty()) {
            // ✅ мягкий fallback на legacy, чтобы не ломать тюнинг/бэктест до миграции всех вызовов addCandle(...)
            List<Candle> legacy = getCandles(sym, tf, limit);
            if (!legacy.isEmpty() && log.isDebugEnabled()) {
                log.debug("🟡 Using LEGACY candles as fallback (ex={}, net={}, sym={}, tf={}, size={})",
                        ex, network, sym, tf, legacy.size());
            }
            return legacy;
        }

        return readFromDeque(deque, limit);
    }

    private List<Candle> readFromDeque(Deque<Candle> deque, int limit) {
        synchronized (deque) {
            if (limit <= 0 || deque.size() <= limit) {
                return new ArrayList<>(deque);
            }

            List<Candle> result = new ArrayList<>(limit);
            Iterator<Candle> it = deque.descendingIterator();

            while (it.hasNext() && result.size() < limit) {
                result.add(it.next());
            }

            Collections.reverse(result);
            return result;
        }
    }

    // ============================
    // EXTRA
    // ============================

    public Candle getLast(String symbol, String timeframe) {
        String sym = normSymbolOrNull(symbol);
        String tf = normTfOrNull(timeframe);
        if (sym == null || tf == null) return null;

        Map<String, Deque<Candle>> tfMap = legacyCache.get(sym);
        if (tfMap == null) return null;

        Deque<Candle> deque = tfMap.get(tf);
        if (deque == null) return null;

        synchronized (deque) {
            return deque.peekLast();
        }
    }

    public Candle getLast(String exchange, NetworkType network, String symbol, String timeframe) {
        if (exchange == null || network == null) {
            return getLast(symbol, timeframe);
        }

        String ex = normExchangeOrNull(exchange);
        String sym = normSymbolOrNull(symbol);
        String tf = normTfOrNull(timeframe);
        if (ex == null || sym == null || tf == null) return null;

        Deque<Candle> deque = getEnvDeque(ex, network, sym, tf, false);
        if (deque == null || deque.isEmpty()) {
            return getLast(sym, tf); // мягкий fallback
        }

        synchronized (deque) {
            return deque.peekLast();
        }
    }

    public void clear(String symbol) {
        String sym = normSymbolOrNull(symbol);
        if (sym == null) return;

        legacyCache.remove(sym);

        // удаляем этот symbol из envCache во всех ex/net
        for (Map<NetworkType, Map<String, Map<String, Deque<Candle>>>> netMap : envCache.values()) {
            if (netMap == null) continue;
            for (Map<String, Map<String, Deque<Candle>>> symMap : netMap.values()) {
                if (symMap == null) continue;
                symMap.remove(sym);
            }
        }
    }

    public void clear(String exchange, NetworkType network, String symbol) {
        String ex = normExchangeOrNull(exchange);
        String sym = normSymbolOrNull(symbol);
        if (ex == null || network == null || sym == null) return;

        Map<NetworkType, Map<String, Map<String, Deque<Candle>>>> netMap = envCache.get(ex);
        if (netMap == null) return;

        Map<String, Map<String, Deque<Candle>>> symMap = netMap.get(network);
        if (symMap == null) return;

        symMap.remove(sym);
    }

    public void setMaxCandles(int max) {
        if (max < 200) max = 200;
        this.maxCandles = max;

        // (опционально) подчистим текущие деки
        trimAllDeques(legacyCache);
        trimAllDequesEnv();
    }

    private void trimAllDeques(Map<String, Map<String, Deque<Candle>>> root) {
        if (root == null) return;
        for (Map<String, Deque<Candle>> tfMap : root.values()) {
            if (tfMap == null) continue;
            for (Deque<Candle> d : tfMap.values()) {
                if (d == null) continue;
                synchronized (d) {
                    while (d.size() > maxCandles) d.pollFirst();
                }
            }
        }
    }

    private void trimAllDequesEnv() {
        for (Map<NetworkType, Map<String, Map<String, Deque<Candle>>>> netMap : envCache.values()) {
            if (netMap == null) continue;
            for (Map<String, Map<String, Deque<Candle>>> symMap : netMap.values()) {
                if (symMap == null) continue;
                for (Map<String, Deque<Candle>> tfMap : symMap.values()) {
                    if (tfMap == null) continue;
                    for (Deque<Candle> d : tfMap.values()) {
                        if (d == null) continue;
                        synchronized (d) {
                            while (d.size() > maxCandles) d.pollFirst();
                        }
                    }
                }
            }
        }
    }

    public Map<String, Integer> stats() {
        Map<String, Integer> m = new HashMap<>();

        // legacy
        for (var e : legacyCache.entrySet()) {
            int sum = e.getValue().values().stream().mapToInt(Deque::size).sum();
            m.put("LEGACY:" + e.getKey(), sum);
        }

        // env
        for (var exE : envCache.entrySet()) {
            String ex = exE.getKey();
            Map<NetworkType, Map<String, Map<String, Deque<Candle>>>> netMap = exE.getValue();
            if (netMap == null) continue;

            for (var netE : netMap.entrySet()) {
                NetworkType net = netE.getKey();
                Map<String, Map<String, Deque<Candle>>> symMap = netE.getValue();
                if (symMap == null) continue;

                for (var symE : symMap.entrySet()) {
                    String sym = symE.getKey();
                    Map<String, Deque<Candle>> tfMap = symE.getValue();
                    if (tfMap == null) continue;

                    int sum = tfMap.values().stream().mapToInt(Deque::size).sum();
                    m.put(ex + ":" + net + ":" + sym, sum);
                }
            }
        }

        return m;
    }

    // ============================
    // internals
    // ============================

    private Deque<Candle> getEnvDeque(String exchange,
                                     NetworkType network,
                                     String symbol,
                                     String timeframe,
                                     boolean create) {

        Map<NetworkType, Map<String, Map<String, Deque<Candle>>>> netMap =
                create
                        ? envCache.computeIfAbsent(exchange, k -> new ConcurrentHashMap<>())
                        : envCache.get(exchange);

        if (netMap == null) return null;

        Map<String, Map<String, Deque<Candle>>> symMap =
                create
                        ? netMap.computeIfAbsent(network, k -> new ConcurrentHashMap<>())
                        : netMap.get(network);

        if (symMap == null) return null;

        Map<String, Deque<Candle>> tfMap =
                create
                        ? symMap.computeIfAbsent(symbol, k -> new ConcurrentHashMap<>())
                        : symMap.get(symbol);

        if (tfMap == null) return null;

        return create
                ? tfMap.computeIfAbsent(timeframe, k -> new ConcurrentLinkedDeque<>())
                : tfMap.get(timeframe);
    }
}