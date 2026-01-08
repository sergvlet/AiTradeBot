package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

public interface PositionStore {
    boolean isInPosition(Long chatId, StrategyType type, String exchange, NetworkType network);

    void markOpened(Long chatId, StrategyType type, String exchange, NetworkType network);

    void markClosed(Long chatId, StrategyType type, String exchange, NetworkType network);
}
