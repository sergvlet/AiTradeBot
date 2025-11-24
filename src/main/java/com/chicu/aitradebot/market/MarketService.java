package com.chicu.aitradebot.market;

import com.chicu.aitradebot.strategy.core.CandleProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * MarketService — единая точка получения:
 * - свечей
 * - текущей цены
 * - исторических свечей (подгрузка)
 * - данных для тренда
 *
 * MarketService НЕ зависит от Web.
 * MarketService НЕ зависит от конкретной биржи.
 * Биржи живут внутри ExchangeClient.
 */
public interface MarketService {

    /**
     * Получить свежее значение цены для symbol.
     */
    BigDecimal getCurrentPrice(Long chatId, String symbol);

    /**
     * Загрузить свечи (ограничение по количеству).
     * Используется для первичной загрузки графика.
     */
    List<CandleProvider.Candle> loadCandles(
            Long chatId,
            String symbol,
            String timeframe,
            int limit
    );

    /**
     * Догрузить свечи назад (исторические).
     */
    List<CandleProvider.Candle> loadMoreCandles(
            Long chatId,
            String symbol,
            String timeframe,
            Instant to,
            int limit
    );

    /**
     * Получить процентное изменение цены (для тренда).
     */
    BigDecimal getChangePct(
            Long chatId,
            String symbol,
            String timeframe
    );



}
