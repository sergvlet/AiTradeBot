package com.chicu.aitradebot.strategy.core;

/**
 * Минимальный интерфейс для стратегий, которым нужен контекст (chatId, symbol).
 * Заглушка для совместимости со старыми стратегиями.
 */
public interface ContextAwareStrategy {

    /**
     * Установить контекст для стратегии.
     *
     * @param chatId чат / пользователь
     * @param symbol торговый инструмент, например "BTCUSDT"
     */
    void setContext(long chatId, String symbol);
}
