package com.chicu.aitradebot.strategy;

/**
 * Базовый контракт для любой торговой стратегии.
 * Все стратегии должны реализовывать этот интерфейс.
 */
public interface TradingStrategy {

    /** Telegram chatId пользователя */
    long getChatId();

    /** Символ (BTCUSDT, ETHUSDT и т.д.) */
    String getSymbol();

    /** Название стратегии */
    String getName();

    /** Активен ли сейчас поток стратегии */
    boolean isActive();

    /** Запуск стратегии (инициализация и подписка на рынок) */
    void start();

    /** Остановка стратегии */
    void stop();

    /** Обработка рыночного обновления */
    void onMarketUpdate(String symbol, double price);
}
