package com.chicu.aitradebot.strategy.common;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Унифицированный сигнал от любой стратегии.
 * action: BUY/SELL/HOLD
 * confidence: 0..1 (уверенность сигнала)
 * symbol/price/timestamp — опционально, для логов и ордер-роутинга.
 */
public class TradingSignal {

    public enum Action { BUY, SELL, HOLD }

    private final Action action;
    private final BigDecimal confidence; // 0..1
    private final String symbol;         // например, "BTCUSDT"
    private final BigDecimal price;      // цена в момент сигнала (опц.)
    private final long timestamp;        // System.currentTimeMillis()

    private TradingSignal(Builder b) {
        this.action = Objects.requireNonNull(b.action, "action is required");
        this.confidence = b.confidence == null ? BigDecimal.ONE : b.confidence;
        this.symbol = b.symbol;
        this.price = b.price;
        this.timestamp = b.timestamp == null ? System.currentTimeMillis() : b.timestamp;
    }

    public Action getAction() { return action; }
    public BigDecimal getConfidence() { return confidence; }
    public String getSymbol() { return symbol; }
    public BigDecimal getPrice() { return price; }
    public long getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return "TradingSignal{" +
                "action=" + action +
                ", confidence=" + confidence +
                ", symbol='" + symbol + '\'' +
                ", price=" + price +
                ", timestamp=" + timestamp +
                '}';
    }

    // --------- Статические фабрики для удобства ----------
    public static TradingSignal buy(BigDecimal confidence) {
        return builder().action(Action.BUY).confidence(confidence).build();
    }
    public static TradingSignal sell(BigDecimal confidence) {
        return builder().action(Action.SELL).confidence(confidence).build();
    }
    public static TradingSignal hold(BigDecimal confidence) {
        return builder().action(Action.HOLD).confidence(confidence).build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Action action;
        private BigDecimal confidence;
        private String symbol;
        private BigDecimal price;
        private Long timestamp;

        public Builder action(Action action) { this.action = action; return this; }
        public Builder confidence(BigDecimal confidence) { this.confidence = confidence; return this; }
        public Builder symbol(String symbol) { this.symbol = symbol; return this; }
        public Builder price(BigDecimal price) { this.price = price; return this; }
        public Builder timestamp(Long timestamp) { this.timestamp = timestamp; return this; }
        public TradingSignal build() { return new TradingSignal(this); }
    }
}
