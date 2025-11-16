package com.chicu.aitradebot.market;

import com.chicu.aitradebot.strategy.core.CandleProvider;

import java.time.Instant;
import java.util.*;

/**
 * ⏱️ Агрегатор тиков в свечи (для субминутных таймфреймов).
 */
public class CandleResampler {

    public interface Tick {
        Instant ts();
        double price();
        double qty();
    }

    public static List<CandleProvider.Candle> fromTicks(List<? extends Tick> ticks, int stepSeconds, int limit) {
        if (ticks == null || ticks.isEmpty()) return List.of();
        ticks.sort(Comparator.comparing(Tick::ts));

        Map<Long, Bucket> buckets = new LinkedHashMap<>();
        for (var t : ticks) {
            long bucketSec = (t.ts().getEpochSecond() / stepSeconds) * stepSeconds;
            var b = buckets.computeIfAbsent(bucketSec, Bucket::new);
            b.add(t.price(), t.qty());
        }

        List<CandleProvider.Candle> out = new ArrayList<>(buckets.size());
        for (var b : buckets.values()) out.add(b.toCandle());
        int from = Math.max(0, out.size() - limit);
        return out.subList(from, out.size());
    }

    private static class Bucket {
        final long second;
        double open, high, low, close, volume;
        boolean first = true;

        Bucket(long second) { this.second = second; }

        void add(double price, double qty) {
            if (first) { open = high = low = close = price; first = false; }
            high = Math.max(high, price);
            low = Math.min(low, price);
            close = price;
            volume += qty;
        }

        CandleProvider.Candle toCandle() {
            return new CandleProvider.Candle(
                    Instant.ofEpochSecond(second),
                    open, high, low, close, volume
            );
        }
    }
}
