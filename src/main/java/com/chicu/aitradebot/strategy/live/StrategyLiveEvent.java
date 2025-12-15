package com.chicu.aitradebot.strategy.live;

import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 🔥 ЕДИНЫЙ LIVE-КОНТРАКТ ДЛЯ ВСЕХ СТРАТЕГИЙ (v4 FINAL)
 * Strategy → WebSocket → UI (график)
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyLiveEvent {

    /**
     * Тип события:
     *
     * CORE:
     *  candle | price | trade | state | metric
     *
     * LAYERS:
     *  levels | zone | active_level | trade_zone | tp_sl
     *
     * ORDERS:
     *  order
     *
     * UX:
     *  signal | magnet
     *
     * EXTRA (SCALPING):
     *  price_line | window_zone | atr
     */
    private String type;

    /** Telegram chatId */
    private Long chatId;

    /** Тип стратегии */
    private StrategyType strategyType;

    /** Торговый символ */
    private String symbol;

    /** Время события (epoch millis) */
    private long time;

    // ======================================================
    // PAYLOADS (РОВНО ОДИН ИСПОЛЬЗУЕТСЯ ПО type)
    // ======================================================

    /** Свеча */
    private CandlePayload kline;

    /** Текущая цена */
    private BigDecimal price;

    /** Рыночная сделка */
    private TradePayload trade;

    /** Лимитный / активный ордер */
    private OrderPayload order;

    /** 🟣 УРОВНИ (grid / fib / bb) */
    private List<LevelPayload> levels;

    /** 🟠 Общая зона сетки */
    private ZonePayload zone;

    /** 🔥 Активный уровень (support / resistance) */
    private ActiveLevelPayload activeLevel;

    /** 🔴 BUY / SELL зона */
    private TradeZonePayload tradeZone;

    /** 📍 TP / SL */
    private TpSlPayload tpSl;

    /** 🧲 Магнит к уровню */
    private MagnetPayload magnet;

    /** Состояние стратегии */
    private String state;

    /** Сигнал (confidence / entry / exit) */
    private SignalPayload signal;

    /** Метрика (PnL, confidence, score) */
    private Double metric;

    /** 📍 Линия цены (ENTRY / TP / SL и т.д.) */
    private PriceLinePayload priceLine;

    /** 🔲 Зона окна (high/low) */
    private WindowZonePayload windowZone;

    /** 🧠 ATR / волатильность (если шлёшь в UI) */
    private AtrPayload atr;

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
    // 📌 TRADE (рыночное исполнение)
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class TradePayload {
        private String side; // BUY / SELL
        private BigDecimal price;
        private BigDecimal qty;
    }

    // ======================================================
    // 📌 ORDER (лимитный / активный)
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class OrderPayload {
        private String orderId;
        private String side;   // BUY / SELL
        private BigDecimal price;
        private BigDecimal qty;
        private String status; // NEW / FILLED / CANCELED
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
        private String role;        // SUPPORT / RESISTANCE
        private double distancePct; // расстояние до цены
    }

    // ======================================================
    // 🔴 BUY / SELL ZONE
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class TradeZonePayload {
        private String side; // BUY / SELL
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
        private double strength; // 0..1
    }

    // ======================================================
    // 📈 SIGNAL
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class SignalPayload {
        private String name;
        private double value;
    }

    // ======================================================
    // 🟠 ZONE (общая)
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class ZonePayload {
        private BigDecimal top;
        private BigDecimal bottom;
        private String color;
    }

    // ======================================================
    // 📍 PRICE LINE (entry / tp / sl)
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class PriceLinePayload {
        private String name;   // ENTRY / TP / SL
        private BigDecimal price;
        private String color;  // optional
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
    // 🧠 ATR PAYLOAD
    // ======================================================
    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class AtrPayload {
        private double atr;
        private double volatilityPct;
    }
}
