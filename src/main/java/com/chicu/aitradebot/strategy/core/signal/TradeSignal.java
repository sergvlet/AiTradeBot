package com.chicu.aitradebot.strategy.core.signal;

public record TradeSignal(
        SignalType type,
        double confidence,
        String reason
) {
    public static TradeSignal hold(String reason) {
        return new TradeSignal(SignalType.HOLD, 0.0, reason);
    }

    public static TradeSignal buy(double confidence, String reason) {
        return new TradeSignal(SignalType.BUY, confidence, reason);
    }

    public static TradeSignal sell(double confidence, String reason) {
        return new TradeSignal(SignalType.SELL, confidence, reason);
    }
}
