package com.chicu.aitradebot.strategy.core;

import com.chicu.aitradebot.market.model.MarketTick;

public interface TickAwareStrategy {
    void onPriceUpdate(MarketTick tick);
}
