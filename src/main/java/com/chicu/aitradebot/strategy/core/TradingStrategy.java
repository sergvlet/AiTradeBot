package com.chicu.aitradebot.strategy.core;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Базовый интерфейс торговых стратегий (v4).
 *
 * Каждая стратегия работает в разрезе:
 *  - chatId (пользователь)
 *  - symbol  (торгуемая пара)
 *
 * Интерфейс строго определяет контракт для StrategyEngine и SchedulerService.
 */
public interface TradingStrategy {

    /**
     * Запуск стратегии для конкретного пользователя и символа.
     * Должна:
     *  - загрузить настройки
     *  - инициализировать состояние (state)
     *  - пометить стратегию как активную
     */
    void start(Long chatId, String symbol);

    /**
     * Остановка стратегии для данного пользователя.
     * Должна:
     *  - остановить выполнение
     *  - очистить состояние
     *  - сохранить факт остановки (active = false)
     */
    void stop(Long chatId, String symbol);

    /**
     * Активна ли стратегия для пользователя.
     */
    boolean isActive(Long chatId);

    /**
     * Когда стратегия была запущена (может быть null).
     */
    Instant getStartedAt(Long chatId);

    /**
     * Имя рабочего потока — удобно для логов и мониторинга.
     * По умолчанию стандартное, стратегии могут переопределить.
     */
    default String getThreadName(Long chatId) {
        return "strategy-" + chatId;
    }

    /**
     * Главный метод стратегии — вызывается SchedulerService.
     *
     * @param chatId пользователь
     * @param symbol символ
     * @param price  текущая цена
     * @param ts     время тика
     *
     * Стратегия обязана быть НЕБЛОКИРУЮЩЕЙ и максимально лёгкой.
     */
    void onPriceUpdate(Long chatId, String symbol, BigDecimal price, Instant ts);
    default void replayLayers(Long chatId) {
        // по умолчанию ничего
    }
}
