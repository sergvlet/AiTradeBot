package com.chicu.aitradebot.market;

import com.chicu.aitradebot.market.model.Candle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CandleResampler {

    private CandleResampler() {}

    // =====================================================
    // PUBLIC API
    // =====================================================

    /**
     * Ресемплинг свечей в более крупный таймфрейм.

     * ⏱ ВХОД:
     *  - source.time = epoch millis
     *  - source отсортирован ИЛИ будет отсортирован

     * ⏱ ВЫХОД:
     *  - Candle.time = начало бакета (epoch millis)
     *  - последняя свеча МОЖЕТ быть НЕЗАКРЫТОЙ
     */
    public static List<Candle> resample(
            List<Candle> source,
            String targetTf
    ) {

        if (source == null || source.isEmpty()) {
            return List.of();
        }

        String tf = normalizeTf(targetTf);
        long tfMillis = timeframeToMillis(tf);

        if (tfMillis <= 0) {
            throw new IllegalArgumentException("Unsupported timeframe: " + targetTf);
        }

        // 🔒 страховка: сортируем по времени
        List<Candle> input = source.stream()
                .sorted(Comparator.comparingLong(Candle::getTime))
                .toList();

        List<Candle> out = new ArrayList<>();

        Candle current = null;
        long bucketStart = -1;

        for (Candle c : input) {

            long ts = c.getTime();
            long bucket = (ts / tfMillis) * tfMillis;

            if (current == null || bucket != bucketStart) {

                // закрываем предыдущую свечу
                if (current != null) {
                    current.setClosed(true);
                    out.add(current);
                }

                bucketStart = bucket;

                current = new Candle(
                        bucket,
                        c.getOpen(),
                        c.getHigh(),
                        c.getLow(),
                        c.getClose(),
                        c.getVolume(),
                        false // ❗ новая свеча ВСЕГДА начинается как незакрытая
                );

            } else {
                // обновление текущей свечи
                current.setHigh(Math.max(current.getHigh(), c.getHigh()));
                current.setLow(Math.min(current.getLow(), c.getLow()));
                current.setClose(c.getClose());
                current.setVolume(current.getVolume() + c.getVolume());
            }
        }

        // ❗ ВАЖНО:
        // последнюю свечу НЕ закрываем принудительно
        if (current != null) {
            out.add(current);
        }

        return out;
    }

    // =====================================================
    // TIMEFRAME UTILS
    // =====================================================

    private static String normalizeTf(String tf) {
        return tf == null ? null : tf.trim().toLowerCase();
    }

    private static long timeframeToMillis(String tf) {

        if (tf == null) return -1;

        return switch (tf) {
            case "1m"  -> Duration.ofMinutes(1).toMillis();
            case "3m"  -> Duration.ofMinutes(3).toMillis();
            case "5m"  -> Duration.ofMinutes(5).toMillis();
            case "15m" -> Duration.ofMinutes(15).toMillis();
            case "30m" -> Duration.ofMinutes(30).toMillis();
            case "1h"  -> Duration.ofHours(1).toMillis();
            case "4h"  -> Duration.ofHours(4).toMillis();
            case "1d"  -> Duration.ofDays(1).toMillis();
            default -> -1;
        };
    }
    private boolean isScalpTimeframe(String timeframe) {
        long tfMs = timeframeToMillis(timeframe);
        if (tfMs <= 0) {
            return false;
        }

        return tfMs <= 5L * 60_000L;
    }
}
