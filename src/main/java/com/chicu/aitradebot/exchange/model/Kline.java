package com.chicu.aitradebot.exchange.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Универсальная свеча (kline) для Binance / Bybit и т.п.
 * Порядок полей соответствует тому, как мы создаём Kline:
 * new Kline(openTime, open, high, low, close, volume)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Kline {

    /** Время открытия свечи, ms с 1970 (Binance/Bybit дают так) */
    private long openTime;

    /** Цена открытия */
    private double open;

    /** Максимум */
    private double high;

    /** Минимум */
    private double low;

    /** Цена закрытия */
    private double close;

    /** Объём */
    private double volume;
}
