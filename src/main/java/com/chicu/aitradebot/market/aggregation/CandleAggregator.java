package com.chicu.aitradebot.market.aggregation;

import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionCandleService;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public class CandleAggregator {

    /** symbol -> текущая свеча */
    private final ConcurrentMap<String, SmartFusionCandleService.Candle> current = new ConcurrentHashMap<>();

    /** Новый тик → обновляем свечу */
    public SmartFusionCandleService.Candle update(String symbol, long ts, double price) {

        long sec = ts / 1000 * 1000;
        String key = symbol;

        return current.compute(key, (k, old) -> {
            if (old == null || old.getTime() < sec) {
                // новая свеча
                return new SmartFusionCandleService.Candle(
                        Instant.ofEpochMilli(sec),
                        price, price, price, price
                );
            }
            // обновляем существующую свечу
            double open = old.open();
            double high = Math.max(old.high(), price);
            double low = Math.min(old.low(), price);
            double close = price;

            return new SmartFusionCandleService.Candle(
                    Instant.ofEpochMilli(sec),
                    open, high, low, close
            );
        });
    }
}
