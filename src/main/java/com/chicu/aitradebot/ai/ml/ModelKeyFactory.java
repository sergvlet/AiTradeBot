package com.chicu.aitradebot.ai.ml;

public class ModelKeyFactory {

    public String build(String strategy, String symbol, String timeframe) {
        String s = (strategy == null ? "UNKNOWN" : strategy.trim().toUpperCase());
        String sym = (symbol == null ? "UNKNOWN" : symbol.trim().toUpperCase());
        String tf = (timeframe == null ? "UNKNOWN" : timeframe.trim().toLowerCase());
        return s + ":" + sym + ":" + tf;
    }
}
