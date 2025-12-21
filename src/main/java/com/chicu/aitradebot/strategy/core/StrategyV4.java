package com.chicu.aitradebot.strategy.core;

import com.chicu.aitradebot.strategy.core.context.RuntimeStrategyContext;
import com.chicu.aitradebot.strategy.core.signal.TradeSignal;

/**
 * 🎯 Чистая стратегия (V4)
 * ❌ без состояния
 * ❌ без UI
 * ❌ без ордеров
 */
public interface StrategyV4 {

    TradeSignal evaluate(RuntimeStrategyContext context);
}
