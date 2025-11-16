package com.chicu.aitradebot.market.ws;

import java.math.BigDecimal;

public interface TradeFeedListener {

    /**
     * Универсальное событие trade:
     * @param symbol  BTCUSDT
     * @param price   BigDecimal цена сделки
     * @param ts      timestamp сделки (millis)
     */
    void onTrade(String symbol, BigDecimal price, long ts);
}
