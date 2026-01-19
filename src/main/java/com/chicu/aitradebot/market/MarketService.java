package com.chicu.aitradebot.market;

import com.chicu.aitradebot.exchange.client.ExchangeClient;

import java.math.BigDecimal;
import java.util.List;

/**
 * Единый сервис рынка, работающий через ExchangeClientFactory.
 * Учитывает chatId → exchange + network.
 */
public interface MarketService {


    /**
     * Текущая цена рынка для пользователя.
     */
    BigDecimal getCurrentPrice(Long chatId, String symbol);

    /**
     * Загрузка свечей.
     */
    List<ExchangeClient.Kline> loadKlines(Long chatId,
                                          String symbol,
                                          String interval,
                                          int limit);
}
