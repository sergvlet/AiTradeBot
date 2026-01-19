package com.chicu.aitradebot.strategy.live;

import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;


/**
 * 🔥 ЕДИНЫЙ LIVE-КОНТРАКТ ДЛЯ ВСЕХ СТРАТЕГИЙ (v4 FINAL — STABLE)
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class StrategyLiveEvent {

    // ======================================================
    // 🔑 IDENTITY (для дедупликации)
    // ======================================================

    @EqualsAndHashCode.Include
    private String type;

    @EqualsAndHashCode.Include
    private Long chatId;

    @EqualsAndHashCode.Include
    private StrategyType strategyType;

    @EqualsAndHashCode.Include
    private String symbol;

    // ======================================================
    // 🔥 META (ВАЖНО ДЛЯ UI)
    // ======================================================

    /** ⏱ Таймфрейм события (1m, 5m, 15m, ...) */
    private String timeframe;

    /** 🕒 Время события (epoch millis) */
    private long time;

    // ======================================================
    // PAYLOADS (РОВНО ОДИН ПО type)
    // ======================================================

    private CandlePayload kline;
    private BigDecimal price;
    private TradePayload trade;
    private OrderPayload order;

    private List<LevelPayload> levels;
    private ZonePayload zone;
    private ActiveLevelPayload activeLevel;
    private TradeZonePayload tradeZone;
    private TpSlPayload tpSl;
    private MagnetPayload magnet;

    private String state;
    private SignalPayload signal;
    private Double metric;
    private PriceLinePayload priceLine;
    private WindowZonePayload windowZone;
    private AtrPayload atr;

    // ======================================================
    // NORMALIZATION
    // ======================================================

    public void normalize() {
        if (this.symbol != null) {
            this.symbol = this.symbol.trim().toUpperCase();
            if (this.symbol.isEmpty()) {
                this.symbol = null;
            }
        }

        if (this.timeframe != null) {
            this.timeframe = this.timeframe.trim().toLowerCase();
            if (this.timeframe.isEmpty()) {
                this.timeframe = null;
            }
        }

        if (this.time <= 0) {
            this.time = nowMillis();
        }
    }

    // ======================================================
    // UTILS
    // ======================================================

    public static long nowMillis() {
        return Instant.now().toEpochMilli();
    }

    // ======================================================
    // 🕯 CANDLE
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class CandlePayload {
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private BigDecimal volume;
        private String timeframe;
    }

    // ======================================================
    // 📌 TRADE
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class TradePayload {
        private String side;
        private BigDecimal price;
        private BigDecimal qty;
    }

    // ======================================================
    // 📌 ORDER
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class OrderPayload {
        private String orderId;
        private String side;
        private BigDecimal price;
        private BigDecimal qty;
        private String status;
    }

    // ======================================================
    // 📌 LEVEL
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class LevelPayload {
        private BigDecimal price;
    }

    // ======================================================
    // 🔥 ACTIVE LEVEL
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class ActiveLevelPayload {
        private BigDecimal price;
        private String role;
        private double distancePct;
    }

    // ======================================================
    // 🔴 BUY / SELL ZONE
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class TradeZonePayload {
        private String side;
        private BigDecimal top;
        private BigDecimal bottom;
    }

    // ======================================================
    // 📍 TP / SL
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class TpSlPayload {
        private BigDecimal tp;
        private BigDecimal sl;
    }

    // ======================================================
    // 🧲 MAGNET
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class MagnetPayload {
        private BigDecimal target;
        private double strength;
    }

    // ======================================================
    // 🚦 SIGNAL
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class SignalPayload {
        private String name;       // BUY / SELL / HOLD
        private double confidence;
        private String reason;
        private String timeframe;
    }

    // ======================================================
    // 🟠 ZONE
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class ZonePayload {
        private BigDecimal top;
        private BigDecimal bottom;
        private String color;
    }

    // ======================================================
    // 📍 PRICE LINE
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class PriceLinePayload {
        private String name;
        private BigDecimal price;
        private String color;
    }

    // ======================================================
    // 🔲 WINDOW ZONE
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class WindowZonePayload {
        private BigDecimal high;
        private BigDecimal low;
    }

    // ======================================================
    // 🧠 ATR
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class AtrPayload {
        private double atr;
        private double volatilityPct;
    }
}
