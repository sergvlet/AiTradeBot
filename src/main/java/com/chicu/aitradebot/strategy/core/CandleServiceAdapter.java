package com.chicu.aitradebot.strategy.core;

import java.util.List;

/**
 * Универсальный адаптер для сервисов свечей стратегий.
 */
public interface CandleServiceAdapter<C> {
    List<C> getRecentCandles(long chatId, int limit);
}
