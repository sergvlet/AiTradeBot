package com.chicu.aitradebot.market;

import com.chicu.aitradebot.market.model.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class MarketStreamManager {

    /**
     * Хранилище:
     * SYMBOL → TIMEFRAME → DEQUE<CANDLE>
     */
    private final Map<String, Map<String, Deque<Candle>>> cache = new ConcurrentHashMap<>();

    /** Максимум свечей в памяти */
    private volatile int maxCandles = 1500;

    // ============================
    // NORMALIZATION
    // ============================

    private String normSymbol(String s) {
        if (s == null) return "";
        s = s.trim().toUpperCase(Locale.ROOT);

        int idx = s.indexOf("@");
        if (idx > 0) {
            s = s.substring(0, idx);
        }
        return s;
    }

    private String normTf(String tf) {
        if (tf == null) return "";
        tf = tf.trim().toLowerCase(Locale.ROOT);

        if (tf.startsWith("kline_")) {
            tf = tf.substring(6);
        }
        return tf;
    }

    // ============================
    // WRITE
    // ============================

    public void addCandle(String symbol, String timeframe, Candle candle) {

        if (candle == null) return;

        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        var tfMap = cache.computeIfAbsent(sym, k -> new ConcurrentHashMap<>());
        var deque = tfMap.computeIfAbsent(tf, k -> new ArrayDeque<>());

        synchronized (deque) {

            Candle last = deque.peekLast();

            // первая свеча
            if (last == null) {
                deque.addLast(candle);
                return;
            }

            // обновление текущей свечи
            if (last.getTime() == candle.getTime()) {
                deque.pollLast();
                deque.addLast(candle);
                return;
            }

            // защита от старых данных
            if (candle.getTime() < last.getTime()) {
                log.debug("⏪ Skip old candle {} < {}", candle.getTime(), last.getTime());
                return;
            }

            // новая свеча
            deque.addLast(candle);

            // ограничение размера
            while (deque.size() > maxCandles) {
                deque.pollFirst();
            }
        }
    }

    // ============================
    // READ
    // ============================

    public List<Candle> getCandles(String symbol, String timeframe, int limit) {

        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        var tfMap = cache.get(sym);
        if (tfMap == null) return List.of();

        var deque = tfMap.get(tf);
        if (deque == null || deque.isEmpty()) return List.of();

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
    // EXTRA UTILS
    // ============================

    /** Последняя свеча */
    public Candle getLast(String symbol, String timeframe) {

        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        var tfMap = cache.get(sym);
        if (tfMap == null) return null;

        var deque = tfMap.get(tf);
        if (deque == null) return null;

        synchronized (deque) {
            return deque.peekLast();
        }
    }

    /** Очистка по символу */
    public void clear(String symbol) {
        cache.remove(normSymbol(symbol));
    }

    /** Глобальный лимит */
    public void setMaxCandles(int max) {
        if (max < 200) max = 200;
        this.maxCandles = max;
    }

    /** Debug статистика */
    public Map<String, Integer> stats() {
        Map<String, Integer> m = new HashMap<>();
        for (var e : cache.entrySet()) {
            int sum = e.getValue().values().stream()
                    .mapToInt(Deque::size)
                    .sum();
            m.put(e.getKey(), sum);
        }
        return m;
    }
}
