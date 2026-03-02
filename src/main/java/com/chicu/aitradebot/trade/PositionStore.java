package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Хранилище факта "стратегия в позиции".
 * В V4 учитываем symbol, потому что один chatId/type может торговать разными символами.
 *
 * ✅ Важно:
 * - теперь PositionStore умеет хранить не только факт, но и ДАННЫЕ позиции (entryPrice/qty/tp/sl),
 *   чтобы не терять их между слоями и не путаться при выходе.
 */
public interface PositionStore {

    /**
     * Данные позиции (минимум для корректного выхода и логов).
     */
    record PositionSnapshot(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network,
            String symbol,
            BigDecimal entryPrice,
            BigDecimal qty,
            BigDecimal tp,
            BigDecimal sl,
            BigDecimal quoteSpent,
            Long entryOrderId,
            Instant openedAt
    ) {}

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
     * ⚠️ Старый минимальный контракт: только "факт".
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

    // =====================================================
    // ✅ НОВОЕ: хранение данных позиции (не ломает старый код)
    // =====================================================

    /**
     * Сохранить "факт + данные" позиции.
     * Это будет использовать TradeExecutionService после успешного BUY.
     */
    default void markOpened(Long chatId,
                            StrategyType type,
                            String exchange,
                            NetworkType network,
                            String symbol,
                            BigDecimal entryPrice,
                            BigDecimal qty,
                            BigDecimal tp,
                            BigDecimal sl,
                            BigDecimal quoteSpent,
                            Long entryOrderId,
                            Instant openedAt) {
        // чтобы старые реализации не падали — минимум выставим факт
        markOpened(chatId, type, exchange, network, symbol);
    }

    /**
     * Получить снимок позиции (если храним).
     */
    default Optional<PositionSnapshot> getPosition(Long chatId,
                                                   StrategyType type,
                                                   String exchange,
                                                   NetworkType network,
                                                   String symbol) {
        return Optional.empty();
    }

    /**
     * Удалить позицию полностью (факт + данные).
     * По умолчанию делегируем в markClosed.
     */
    default void clearPosition(Long chatId,
                               StrategyType type,
                               String exchange,
                               NetworkType network,
                               String symbol) {
        markClosed(chatId, type, exchange, network, symbol);
    }

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

    default Optional<PositionSnapshot> getPosition(Long chatId,
                                                   StrategyType type,
                                                   String exchange,
                                                   NetworkType network) {
        return getPosition(chatId, type, exchange, network, null);
    }

    default void clearPosition(Long chatId,
                               StrategyType type,
                               String exchange,
                               NetworkType network) {
        clearPosition(chatId, type, exchange, network, null);
    }
}