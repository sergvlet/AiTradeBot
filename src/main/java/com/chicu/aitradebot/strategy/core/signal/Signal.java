package com.chicu.aitradebot.strategy.core.signal;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class Signal {

    private final SignalType type;     // BUY / SELL / HOLD / EXIT
    private final double price;
    private final double confidence;   // 0..1
    private final String reason;

    public boolean isHold() { return type == SignalType.HOLD; }
    public boolean isBuy()  { return type == SignalType.BUY; }
    public boolean isSell() { return type == SignalType.SELL; }
    public boolean isExit() { return type == SignalType.EXIT; }

    // FACTORY
    public static Signal buy(double price, String reason) {
        return new Signal(SignalType.BUY, price, 1.0, reason);
    }

    public static Signal sell(double price, String reason) {
        return new Signal(SignalType.SELL, price, 1.0, reason);
    }

    public static Signal exit(String reason) {
        return new Signal(SignalType.EXIT, 0.0, 1.0, reason);
    }

    public static Signal hold(String reason) {
        return new Signal(SignalType.HOLD, 0.0, 0.0, reason);
    }
}
