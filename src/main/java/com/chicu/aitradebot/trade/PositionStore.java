package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

public interface PositionStore {

    record PositionSnapshot(
            Long chatId,
            StrategyType strategyType,
            String exchangeName,
            NetworkType networkType,
            String symbol,
            String positionUid,
            BigDecimal entryPrice,
            BigDecimal qty,
            BigDecimal tp,
            BigDecimal sl,
            BigDecimal quoteSpent,
            Long entryOrderId,
            Instant openedAt
    ) {}

    boolean isInPosition(Long chatId,
                         StrategyType type,
                         String exchange,
                         NetworkType network,
                         String symbol);

    void markOpened(Long chatId,
                    StrategyType type,
                    String exchange,
                    NetworkType network,
                    String symbol);

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
        markOpened(chatId, type, exchange, network, symbol, null, entryPrice, qty, tp, sl, quoteSpent, entryOrderId, openedAt);
    }

    void markOpened(Long chatId,
                    StrategyType type,
                    String exchange,
                    NetworkType network,
                    String symbol,
                    String positionUid,
                    BigDecimal entryPrice,
                    BigDecimal qty,
                    BigDecimal tp,
                    BigDecimal sl,
                    BigDecimal quoteSpent,
                    Long entryOrderId,
                    Instant openedAt);

    void markClosed(Long chatId,
                    StrategyType type,
                    String exchange,
                    NetworkType network,
                    String symbol);

    Optional<PositionSnapshot> getPosition(Long chatId,
                                           StrategyType type,
                                           String exchange,
                                           NetworkType network,
                                           String symbol);

    void clearPosition(Long chatId,
                       StrategyType type,
                       String exchange,
                       NetworkType network,
                       String symbol);

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