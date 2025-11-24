package com.chicu.aitradebot.market;

import com.chicu.aitradebot.strategy.core.CandleProvider;

import java.time.Instant;
import java.util.List;

/**
 * Исторические свечи для графика:
 *  - первичная загрузка
 *  - догрузка назад
 */
public interface MarketHistoryService {

    /**
     * Первичная загрузка последних N свечей.
     */
    List<CandleProvider.Candle> loadInitial(
            Long chatId,
            String symbol,
            String timeframe,
            int limit
    );

    /**
     * Догрузка истории "левее" указанного времени.
     * Пока можем просто возвращать последние N свечей
     * (игнорируя to), чтобы не ломать компиляцию.
     */
    List<CandleProvider.Candle> loadMore(
            Long chatId,
            String symbol,
            String timeframe,
            Instant to,
            int limit
    );
}
