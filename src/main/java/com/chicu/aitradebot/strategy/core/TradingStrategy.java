package com.chicu.aitradebot.strategy.core;

import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionCandleService;

/**
 * Базовый интерфейс торговых стратегий.
 * Добавлены обязательные методы жизненного цикла и дефолтный onPriceUpdate.
 */
public interface TradingStrategy {

    /** Запуск стратегии (инициализация ресурсов, проверка контекста и т.п.) */
    void start();

    /** Остановка стратегии (освобождение ресурсов) */
    void stop();

    /** Текущее состояние активности */
    boolean isActive();

    /**
     * Получение события изменения цены. По умолчанию — no-op,
     * чтобы не ломать существующие реализации.
     */
    default void onPriceUpdate(String symbol, double price) {
        // no-op
    }
    default SmartFusionCandleService getCandleService() {
        return null;
    }
    default String getSymbol(long chatId) { return "BTCUSDT"; }

    default double getTakeProfitPct(long chatId) { return 1.0; }

    default double getStopLossPct(long chatId) { return 1.0; }

}
