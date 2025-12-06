package com.chicu.aitradebot.market;

import com.chicu.aitradebot.market.model.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class MarketStreamManager {

    // symbol → timeframe → List<Candle>
    private final Map<String, Map<String, List<Candle>>> cache = new ConcurrentHashMap<>();

    // ============================
    // NORMALIZATION HELPERS
    // ============================

    private String normSymbol(String s) {
        if (s == null) return "";
        s = s.trim().toUpperCase();

        // Binance futures отправляет ETHFDUSD@kline_1m
        int idx = s.indexOf("@");
        if (idx > 0) {
            s = s.substring(0, idx);
        }

        return s;
    }

    private String normTf(String tf) {
        if (tf == null) return "";
        tf = tf.trim().toLowerCase();

        // иногда прилетает kline_1m → оставляем 1m
        if (tf.startsWith("kline_")) {
            return tf.substring(6);
        }

        return tf;
    }

    // ============================
    // WRITE candles
    // ============================

    public void addCandle(String symbol, String timeframe, Candle candle) {

        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        cache.computeIfAbsent(sym, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(tf, k -> new ArrayList<>());

        List<Candle> list = cache.get(sym).get(tf);

        synchronized (list) {

            if (list.isEmpty()) {
                list.add(candle);
                return;
            }

            Candle last = list.get(list.size() - 1);

            // обновление текущей свечи
            if (last.getTime() == candle.getTime()) {
                list.set(list.size() - 1, candle);
                return;
            }

            // новая свеча
            list.add(candle);

            // ограничитель
            if (list.size() > 1000) {
                list.remove(0);
            }
        }
    }

    // ============================
    // READ candles
    // ============================

    public List<Candle> getCandles(String symbol, String timeframe, int limit) {

        String sym = normSymbol(symbol);
        String tf  = normTf(timeframe);

        var tfMap = cache.getOrDefault(sym, Collections.emptyMap());
        var list  = tfMap.getOrDefault(tf, Collections.emptyList());

        if (list.size() <= limit) {
            return new ArrayList<>(list);
        }

        return new ArrayList<>( list.subList(list.size() - limit, list.size()) );
    }
}
