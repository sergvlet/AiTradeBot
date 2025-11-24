package com.chicu.aitradebot.strategy.core;

import java.time.Instant;
import java.util.List;

/**
 * Универсальный поставщик свечей для стратегий, графиков и сервисов.
 *
 * ОДИН общий тип свечи на весь проект:
 *  - time в миллисекундах (long)
 *  - цены и объём в double
 */
public interface CandleProvider {

    /**
     * Базовая свеча:
     *  time (ms) – long
     *  open / high / low / close / volume – double
     */
    record Candle(
            long time,     // миллисекунды, как в WebSocket и Binance
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
        public static Candle fromInstant(
                Instant instant,
                double open,
                double high,
                double low,
                double close,
                double volume
        ) {
            return new Candle(
                    instant.toEpochMilli(),
                    open,
                    high,
                    low,
                    close,
                    volume
            );
        }
    }

    /**
     * Стандартный метод получения последних свечей.
     */
    List<Candle> getRecentCandles(long chatId, String symbol, String timeframe, int limit);
}
