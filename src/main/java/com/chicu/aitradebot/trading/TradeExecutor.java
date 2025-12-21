package com.chicu.aitradebot.trading;

import com.chicu.aitradebot.strategy.core.context.StrategyContext;
import com.chicu.aitradebot.strategy.core.signal.Signal;

public interface TradeExecutor {

    void execute(StrategyContext ctx, Signal signal);

}
