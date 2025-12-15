package com.chicu.aitradebot.market;

import com.chicu.aitradebot.market.model.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class MarketStreamManager {

    /**
     * Хранилище:
     * SYMBOL → TIMEFRAME → LIST<CANDLE>
     *
     * CopyOnWriteArrayList безопаснее и быстрее для frequent-read / rare-write.
     */
    private final Map<String, Map<String, List<Candle>>> cache = new ConcurrentHashMap<>();

    // Максимум свечей — можно менять динамически
    private volatile int maxCandles = 1500;


    // ============================
    // NORMALIZATION
    // ============================

    private String normSymbol(String s) {
        if (s == null) return "";
        s = s.trim().toUpperCase();

        int idx = s.indexOf("@");
        if (idx > 0) s = s.substring(0, idx);

        return s;
    }

    private String normTf(String tf) {
        if (tf == null) return "";
        tf = tf.trim().toLowerCase();

        if (tf.startsWith("kline_"))
            return tf.substring(6);

        return tf;
    }


    // ============================
    // WRITE
    // ============================

    public void addCandle(String symbol, String timeframe, Candle candle) {
        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        var tfMap = cache.computeIfAbsent(sym, k -> new ConcurrentHashMap<>());
        var list  = tfMap.computeIfAbsent(tf, k -> new CopyOnWriteArrayList<>());

        if (list.isEmpty()) {
            list.add(candle);
            return;
        }

        Candle last = list.get(list.size() - 1);

        // обновление текущей свечи (same open-time)
        if (last.getTime() == candle.getTime()) {
            list.set(list.size() - 1, candle);
            return;
        }

        // новая свеча
        list.add(candle);

        // ограничение длины
        if (list.size() > maxCandles) {
            list.subList(0, list.size() - maxCandles).clear();
        }
    }


    // ============================
    // READ
    // ============================

    public List<Candle> getCandles(String symbol, String timeframe, int limit) {
        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        var tfMap = cache.getOrDefault(sym, Collections.emptyMap());
        var list  = tfMap.getOrDefault(tf, Collections.emptyList());

        if (limit <= 0 || list.size() <= limit)
            return new ArrayList<>(list);

        return new ArrayList<>(
                list.subList(list.size() - limit, list.size())
        );
    }


    // ============================
    // EXTRA UTILS (очень полезно)
    // ============================

    /** Возвращает последнюю свечу */
    public Candle getLast(String symbol, String timeframe) {
        List<Candle> l = getCandles(symbol, timeframe, 1);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Очищает все данные по символу */
    public void clear(String symbol) {
        cache.remove(normSymbol(symbol));
    }

    /** Задаёт глобальный лимит свечей */
    public void setMaxCandles(int max) {
        if (max < 200) max = 200;
        this.maxCandles = max;
    }

    /** Debug статистика */
    public Map<String, Integer> stats() {
        Map<String, Integer> m = new HashMap<>();
        for (var e : cache.entrySet()) {
            int sum = e.getValue().values().stream().mapToInt(List::size).sum();
            m.put(e.getKey(), sum);
        }
        return m;
    }
}
