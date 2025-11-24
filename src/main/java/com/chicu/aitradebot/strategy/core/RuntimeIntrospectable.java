package com.chicu.aitradebot.strategy.core;

import java.time.Instant;

/**
 * Интерфейс для получения минимальной runtime-информации о стратегии.
 * Упрощённая заглушка, чтобы не падала компиляция.
 */
public interface RuntimeIntrospectable {

    /**
     * Символ, по которому сейчас работает стратегия.
     */
    String getSymbol();

    /**
     * Время старта стратегии.
     */
    Instant getStartedAt();

    /**
     * Имя рабочего потока (если есть).
     */
    String getThreadName();

    /**
     * Последнее «событие» / сигнал (BUY/SELL/HOLD и т.п.).
     * По умолчанию null — можно не переопределять.
     */
    default String getLastEvent() {
        return null;
    }
}
