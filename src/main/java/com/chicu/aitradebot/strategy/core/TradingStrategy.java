package com.chicu.aitradebot.strategy.core;

import com.chicu.aitradebot.common.enums.NetworkType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Базовый интерфейс торговых стратегий (v4).
 *
 * Каждая стратегия работает в разрезе:
 *  - chatId (пользователь)
 *  - symbol  (торгуемая пара)
 */
public interface TradingStrategy {

    /**
     * Запуск стратегии для конкретного пользователя и символа.
     */
    void start(Long chatId, String symbol);

    /**
     * Расширенный запуск: с указанием exchange/network.
     * В PROD это нужно, чтобы стратегия однозначно выбрала правильную StrategySettings запись.
     *
     * exchange/network могут быть null (если вызвали старым методом).
     * По умолчанию делегирует на старый start(), чтобы не ломать существующие стратегии.
     */
    default void start(Long chatId, String symbol, String exchange, NetworkType network) {
        start(chatId, symbol);
    }

    /**
     * Остановка стратегии для данного пользователя.
     */
    void stop(Long chatId, String symbol);

    /**
     * Расширенная остановка (симметрия с start).
     * По умолчанию делегирует на старый stop().
     */
    default void stop(Long chatId, String symbol, String exchange, NetworkType network) {
        stop(chatId, symbol);
    }

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
     */
    default String getThreadName(Long chatId) {
        return "strategy-" + chatId;
    }

    /**
     * Главный метод стратегии — вызывается SchedulerService.
     */
    void onPriceUpdate(Long chatId, String symbol, BigDecimal price, Instant ts);

    default void replayLayers(Long chatId) {
        // по умолчанию ничего
    }
}
