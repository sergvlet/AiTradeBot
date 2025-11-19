package com.chicu.aitradebot.market;

public interface MarketTickListener {
    void onTick(String symbol, double volume, long timestamp, double price);
}
