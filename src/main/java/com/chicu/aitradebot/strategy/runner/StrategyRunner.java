package com.chicu.aitradebot.strategy.runner;

public interface StrategyRunner {

    void onTick(
            Long chatId,
            String symbol,
            String exchange,
            com.chicu.aitradebot.common.enums.NetworkType networkType
    );
}
