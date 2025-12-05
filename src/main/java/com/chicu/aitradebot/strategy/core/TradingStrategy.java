package com.chicu.aitradebot.strategy.core;

import java.math.BigDecimal;

/**
 * Базовый интерфейс торговых стратегий.
 * Минимальный контракт: старт, стоп, статус и опциональный callback цены.
 */
public interface TradingStrategy {

    /** Запуск стратегии (инициализация ресурсов, проверка контекста и т.п.) */
    void start();

    /** Остановка стратегии (освобождение ресурсов) */
    void stop();

    /** Текущее состояние активности */
    boolean isActive();

    /**
     * Событие изменения цены (если кому-то нужно real-time).
     * По умолчанию ничего не делает, чтобы не ломать реализации.
     */
    default void onPriceUpdate(String symbol, BigDecimal price) {
        // no-op
    }
}
