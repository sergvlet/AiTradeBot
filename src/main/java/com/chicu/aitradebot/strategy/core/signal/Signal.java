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

    // ================================
    // FACTORY METHODS
    // ================================
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
