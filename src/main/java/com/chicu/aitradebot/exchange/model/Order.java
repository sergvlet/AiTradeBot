package com.chicu.aitradebot.exchange.model;

import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.*;

import java.math.BigDecimal;

/**
 * 💹 DTO ордера — используется между стратегиями, API и графиком.
 * ❗ НЕ Entity
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    // ===== Идентификаторы =====

    /** id ордера в локальной БД (если есть) */
    private Long id;

    /** id ордера на бирже */
    private String orderId;

    /** chatId пользователя */
    private Long chatId;

    // ===== Основные поля =====

    /** Символ, например BTCUSDT */
    private String symbol;

    /** BUY / SELL */
    private String side;

    /** MARKET / LIMIT / OCO */
    private String type;

    /**
     * Старое поле количества (legacy).
     */
    @Deprecated
    private BigDecimal qty;

    /**
     * Новое поле количества.
     */
    private BigDecimal quantity;

    /** Цена ордера (limit) или ожидаемая */
    private BigDecimal price;

    /** Средняя цена исполнения */
    private BigDecimal avgPrice;

    /** Фактически исполненное количество */
    private BigDecimal executedQty;

    /** Статус: NEW / FILLED / PARTIALLY_FILLED / CANCELED */
    private String status;

    /**
     * Старое поле времени (ms).
     */
    @Deprecated
    private Long timestamp;

    /**
     * Новое поле времени (ms).
     */
    private Long time;

    /** Исполнен ли полностью */
    private boolean filled;

    /** Стратегия-инициатор */
    private StrategyType strategyType;

    // =====================================================
    // Legacy sync
    // =====================================================

    public BigDecimal getQuantity() {
        return quantity != null ? quantity : qty;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
        this.qty = quantity;
    }

    public Long getTime() {
        return time != null ? time : timestamp;
    }

    public void setTime(Long time) {
        this.time = time;
        this.timestamp = time;
    }

    // =====================================================
    // Safety helpers (НЕ ЛОМАЮТ старый код)
    // =====================================================

    public String getSideUpper() {
        return side != null ? side.toUpperCase() : null;
    }

    public String getTypeUpper() {
        return type != null ? type.toUpperCase() : null;
    }

    // =====================================================
    // Factory — РЕКОМЕНДУЕМО для стратегий
    // =====================================================

    public static Order market(
            Long chatId,
            String symbol,
            String side,
            BigDecimal quantity,
            BigDecimal executionPrice,
            StrategyType strategyType
    ) {
        Order o = new Order();
        o.chatId = chatId;
        o.symbol = symbol;
        o.side = side.toUpperCase();
        o.type = "MARKET";
        o.setQuantity(quantity);
        o.price = executionPrice;
        o.avgPrice = executionPrice;
        o.executedQty = quantity;
        o.status = "FILLED";
        o.filled = true;
        o.time = System.currentTimeMillis();
        o.strategyType = strategyType;
        return o;
    }
}
