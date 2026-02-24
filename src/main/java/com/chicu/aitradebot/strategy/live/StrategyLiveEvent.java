package com.chicu.aitradebot.strategy.live;

import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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

        // type
        if (this.type != null) {
            this.type = this.type.trim();
            if (this.type.isEmpty()) this.type = null;
        }

        // symbol
        if (this.symbol != null) {
            this.symbol = this.symbol.trim().toUpperCase(Locale.ROOT);
            if (this.symbol.isEmpty()) this.symbol = null;
        }

        // timeframe
        if (this.timeframe != null) {
            this.timeframe = this.timeframe.trim().toLowerCase(Locale.ROOT);
            if (this.timeframe.isEmpty()) this.timeframe = null;
        }

        // payload timeframes (на всякий случай)
        if (this.kline != null && this.kline.timeframe != null) {
            this.kline.timeframe = this.kline.timeframe.trim().toLowerCase(Locale.ROOT);
            if (this.kline.timeframe.isEmpty()) this.kline.timeframe = null;
        }
        if (this.signal != null && this.signal.timeframe != null) {
            this.signal.timeframe = this.signal.timeframe.trim().toLowerCase(Locale.ROOT);
            if (this.signal.timeframe.isEmpty()) this.signal.timeframe = null;
        }

        if (this.time <= 0) {
            this.time = nowMillis();
        }
    }

    // ======================================================
    // ✅ DEDUP HASH (для StrategyLiveWsBridge)
    // ======================================================

    /**
     * hash НЕ включает time, чтобы дедуп не ломался из-за "тикания" времени.
     * price/candle всё равно не дедупим.
     */
    public int dedupHash() {
        int h = Objects.hash(type, chatId, strategyType, symbol, timeframe);

        if (price != null) h = 31 * h + normBd(price).hashCode();

        if (kline != null) {
            h = 31 * h + normBd(kline.open).hashCode();
            h = 31 * h + normBd(kline.high).hashCode();
            h = 31 * h + normBd(kline.low).hashCode();
            h = 31 * h + normBd(kline.close).hashCode();
            h = 31 * h + normBd(kline.volume).hashCode();
            h = 31 * h + Objects.hashCode(kline.timeframe);
        }

        if (trade != null) {
            h = 31 * h + Objects.hashCode(trade.side);
            h = 31 * h + normBd(trade.price).hashCode();
            h = 31 * h + normBd(trade.qty).hashCode();
        }

        if (order != null) {
            h = 31 * h + Objects.hashCode(order.orderId);
            h = 31 * h + Objects.hashCode(order.side);
            h = 31 * h + normBd(order.price).hashCode();
            h = 31 * h + normBd(order.qty).hashCode();
            h = 31 * h + Objects.hashCode(order.status);
        }

        if (levels != null) {
            h = 31 * h + levels.size();
            for (LevelPayload lp : levels) {
                if (lp == null || lp.price == null) continue;
                h = 31 * h + normBd(lp.price).hashCode();
            }
        }

        if (zone != null) {
            h = 31 * h + normBd(zone.top).hashCode();
            h = 31 * h + normBd(zone.bottom).hashCode();
            h = 31 * h + Objects.hashCode(zone.color);
        }

        if (activeLevel != null) {
            h = 31 * h + normBd(activeLevel.price).hashCode();
            h = 31 * h + Objects.hashCode(activeLevel.role);
            h = 31 * h + Double.hashCode(activeLevel.distancePct);
        }

        if (tradeZone != null) {
            h = 31 * h + Objects.hashCode(tradeZone.side);
            h = 31 * h + normBd(tradeZone.top).hashCode();
            h = 31 * h + normBd(tradeZone.bottom).hashCode();
        }

        if (tpSl != null) {
            h = 31 * h + normBd(tpSl.tp).hashCode();
            h = 31 * h + normBd(tpSl.sl).hashCode();
        }

        if (magnet != null) {
            h = 31 * h + normBd(magnet.target).hashCode();
            h = 31 * h + Double.hashCode(magnet.strength);
        }

        if (signal != null) {
            h = 31 * h + Objects.hashCode(signal.name);
            h = 31 * h + Objects.hashCode(signal.reason);
            h = 31 * h + Objects.hashCode(signal.timeframe);
            h = 31 * h + Objects.hashCode(signal.confidence);
        }

        if (state != null) h = 31 * h + state.hashCode();
        if (metric != null) h = 31 * h + metric.hashCode();

        if (priceLine != null) {
            h = 31 * h + Objects.hashCode(priceLine.name);
            h = 31 * h + normBd(priceLine.price).hashCode();
            h = 31 * h + Objects.hashCode(priceLine.color);
        }

        if (windowZone != null) {
            h = 31 * h + normBd(windowZone.high).hashCode();
            h = 31 * h + normBd(windowZone.low).hashCode();
        }

        if (atr != null) {
            h = 31 * h + Double.hashCode(atr.atr);
            h = 31 * h + Double.hashCode(atr.volatilityPct);
        }

        return h;
    }

    private static BigDecimal normBd(BigDecimal v) {
        if (v == null) return BigDecimal.ZERO;
        try {
            return v.stripTrailingZeros();
        } catch (Exception ignore) {
            return v;
        }
    }

    public static long nowMillis() {
        return Instant.now().toEpochMilli();
    }

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

    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class TradePayload {
        private String side;
        private BigDecimal price;
        private BigDecimal qty;
    }

    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class OrderPayload {
        private String orderId;
        private String side;
        private BigDecimal price;
        private BigDecimal qty;
        private String status;
    }

    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class LevelPayload {
        private BigDecimal price;
    }

    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class ActiveLevelPayload {
        private BigDecimal price;
        private String role;
        private double distancePct;
    }

    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class TradeZonePayload {
        private String side;
        private BigDecimal top;
        private BigDecimal bottom;
    }

    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class TpSlPayload {
        private BigDecimal tp;
        private BigDecimal sl;
    }

    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class MagnetPayload {
        private BigDecimal target;
        private double strength;
    }

    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class SignalPayload {
        private String name;
        private Double confidence;
        private String reason;
        private String timeframe;
    }

    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class ZonePayload {
        private BigDecimal top;
        private BigDecimal bottom;
        private String color;
    }

    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class PriceLinePayload {
        private String name;
        private BigDecimal price;
        private String color;
    }

    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class WindowZonePayload {
        private BigDecimal high;
        private BigDecimal low;
    }

    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class AtrPayload {
        private double atr;
        private double volatilityPct;
    }
}