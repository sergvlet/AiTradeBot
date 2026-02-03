package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

/**
 * Хранилище факта "стратегия в позиции".
 * В V4 учитываем symbol, потому что один chatId/type может торговать разными символами.
 */
public interface PositionStore {

    /**
     * Точный режим: проверка по (chatId, type, exchange, network, symbol).
     * Если symbol == null/blank — проверяем наличие ЛЮБОЙ позиции для (chatId, type, exchange, network).
     */
    boolean isInPosition(Long chatId,
                         StrategyType type,
                         String exchange,
                         NetworkType network,
                         String symbol);

    /**
     * Отметить, что позиция открыта (контекст + symbol).
     */
    void markOpened(Long chatId,
                    StrategyType type,
                    String exchange,
                    NetworkType network,
                    String symbol);

    /**
     * Отметить, что позиция закрыта (контекст + symbol).
     */
    void markClosed(Long chatId,
                    StrategyType type,
                    String exchange,
                    NetworkType network,
                    String symbol);

    // ----------------------------------------------------------------------
    // ✅ BACKWARD COMPATIBILITY
    // Чтобы старый код (без symbol) не падал при компиляции.
    // ----------------------------------------------------------------------

    default boolean isInPosition(Long chatId,
                                 StrategyType type,
                                 String exchange,
                                 NetworkType network) {
        return isInPosition(chatId, type, exchange, network, null);
    }

    default void markOpened(Long chatId,
                            StrategyType type,
                            String exchange,
                            NetworkType network) {
        markOpened(chatId, type, exchange, network, null);
    }

    default void markClosed(Long chatId,
                            StrategyType type,
                            String exchange,
                            NetworkType network) {
        markClosed(chatId, type, exchange, network, null);
    }
}
