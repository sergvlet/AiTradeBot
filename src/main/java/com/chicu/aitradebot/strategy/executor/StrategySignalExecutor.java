package com.chicu.aitradebot.strategy.executor;

import com.chicu.aitradebot.strategy.core.context.StrategyContext;
import com.chicu.aitradebot.strategy.core.signal.Signal;

public interface StrategySignalExecutor {

    void execute(Signal signal, StrategyContext context);

}
