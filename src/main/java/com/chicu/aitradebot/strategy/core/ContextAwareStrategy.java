package com.chicu.aitradebot.strategy.core;

/**
 * Интерфейс для стратегий, которым нужен контекст (chatId, symbol).
 * Реализуется такими классами, как SmartFusion, Scalping и т.д.
 */
public interface ContextAwareStrategy {
    void setContext(long chatId, String symbol);
}
