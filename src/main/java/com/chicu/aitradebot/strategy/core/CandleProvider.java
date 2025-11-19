package com.chicu.aitradebot.strategy.core;

import java.util.List;

/**
 * Универсальная свеча для дашбордов, стратегий, WebSocket и FullChartApiController.
 * Основана на long timestamp (в миллисекундах) – полностью совместима с frontend.
 */
public interface CandleProvider {

    /**
     * Базовая свеча:
     *  time (ms) – long
     *  open / high / low / close / volume – double
     */
    record Candle(
            long time,     // <-- миллисекунды, как в WebSocket и Binance
            double open,
            double high,
            double low,
            double close,
            double volume
    ) {

        public long getTime()   { return time; }
        public double getOpen() { return open; }
        public double getHigh() { return high; }
        public double getLow()  { return low; }
        public double getClose(){ return close; }
        public double getVolume(){ return volume; }

        /** Удобный конструктор для Instant → long */
        public static Candle fromInstant(java.time.Instant i, double o, double h, double l, double c, double v) {
            return new Candle(i.toEpochMilli(), o, h, l, c, v);
        }
    }

    /** Стандартный метод получения свечей */
    List<Candle> getRecentCandles(long chatId, String symbol, String timeframe, int limit);
}
