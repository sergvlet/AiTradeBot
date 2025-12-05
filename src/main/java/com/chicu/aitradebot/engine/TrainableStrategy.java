package com.chicu.aitradebot.engine;

import com.chicu.aitradebot.strategy.core.TradingStrategy;

/**
 * Расширение базовой стратегии: поддержка обучения перед стартом.
 *
 * Контракт:
 *  1) Сначала вызываем train(chatId, symbol)
 *  2) Потом start()
 *  3) Потом движок вызывает onPriceUpdate(...) по расписанию
 */
public interface TrainableStrategy extends TradingStrategy {

    /**
     * Обучение/подготовка стратегии перед запуском.
     *
     * @param chatId владелец стратегии
     * @param symbol торговый инструмент (BTCUSDT и т.п.)
     */
    void train(long chatId, String symbol);
}
