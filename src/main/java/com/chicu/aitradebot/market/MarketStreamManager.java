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

    public void addCandle(String symbol, String timeframe, Candle candle) {

        cache.computeIfAbsent(symbol, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(timeframe, k -> new ArrayList<>());

        List<Candle> list = cache.get(symbol).get(timeframe);

        synchronized (list) {

            if (list.isEmpty()) {
                list.add(candle);
                return;
            }

            Candle last = list.get(list.size() - 1);

            // обновляем формирующуюся свечу
            if (last.getTime() == candle.getTime()) {
                list.set(list.size() - 1, candle);

                // если свеча закрылась → она теперь окончательная
                if (candle.isClosed()) {
                    // создаём новую свечу (формирующуюся)
                    // Binance сам пришлёт её временем t следующей свечи
                }

                return;
            }

            // свеча с НОВЫМ ts
            list.add(candle);

            // ограничение по хранению
            if (list.size() > 1000) {
                list.remove(0);
            }
        }
    }


    public List<Candle> getCandles(String symbol, String timeframe, int limit) {
        var tfMap = cache.getOrDefault(symbol, Collections.emptyMap());
        var list = tfMap.getOrDefault(timeframe, Collections.emptyList());

        if (list.size() <= limit) {
            return new ArrayList<>(list);
        }

        return new ArrayList<>(list.subList(list.size() - limit, list.size()));
    }
}
