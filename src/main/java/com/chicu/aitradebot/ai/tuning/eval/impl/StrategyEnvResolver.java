package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

public interface StrategyEnvResolver {
    Env resolve(long chatId, StrategyType type);

    record Env(String exchangeName, NetworkType networkType) {}
}
