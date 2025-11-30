package com.chicu.aitradebot.domain.settings;

import com.chicu.aitradebot.common.enums.StrategyType;

public interface StrategySettings {

    Long getId();

    long getChatId();
    void setChatId(long chatId);

    StrategyType getStrategyType();
    void setStrategyType(StrategyType type);
}
