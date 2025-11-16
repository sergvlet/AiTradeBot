package com.chicu.aitradebot.exchange;

import com.chicu.aitradebot.common.enums.NetworkType;

import java.util.List;

/**
 * ExchangeClient — единый интерфейс для работы с любой биржей.
 */
public interface ExchangeClient {

    /**
     * Возвращает свечи (klines) для символа.
     */
    List<Kline> getKlines(String symbol, String interval, int limit);

    /**
     * Возвращает последнюю рыночную цену.
     */
    double getLastPrice(String symbol);

    /**
     * Возвращает имя биржи (BINANCE / BYBIT / OKX / KUCOIN).
     */
    String getExchangeName();

    /**
     * Возвращает тип сети (MAINNET / TESTNET).
     */
    NetworkType getNetworkType();

    /**
     * DTO свечи.
     */
    record Kline(long openTime, double open, double high, double low, double close) {}
}
