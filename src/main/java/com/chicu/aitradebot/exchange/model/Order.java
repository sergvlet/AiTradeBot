package com.chicu.aitradebot.exchange.model;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO ордера между стратегиями, сервисами и UI.
 * Не является JPA Entity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    /** id ордера в локальной БД */
    private Long id;

    /** id ордера на бирже */
    private String orderId;

    /** chatId пользователя */
    private Long chatId;

    /** Торговая пара, например BTCUSDT */
    private String symbol;

    /** BUY / SELL */
    private String side;

    /** MARKET / LIMIT / OCO */
    private String type;

    /**
     * Старое поле количества.
     * Оставлено для совместимости.
     */
    @Deprecated
    private BigDecimal qty;

    /** Новое поле количества */
    private BigDecimal quantity;

    /** Цена ордера или фактическая цена исполнения */
    private BigDecimal price;

    /** Средняя цена исполнения */
    private BigDecimal avgPrice;

    /** Фактически исполненное количество */
    private BigDecimal executedQty;

    /** Статус ордера */
    private String status;

    /**
     * Старое поле времени.
     * Оставлено для совместимости.
     */
    @Deprecated
    private Long timestamp;

    /** Новое поле времени */
    private Long time;

    /** Исполнен ли ордер полностью */
    private boolean filled;

    /** Стратегия-инициатор */
    private StrategyType strategyType;

    /** Биржа исполнения */
    private String exchangeName;

    /** Сеть исполнения */
    private NetworkType networkType;

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
    // Safety helpers
    // =====================================================

    public String getSideUpper() {
        return side != null ? side.toUpperCase() : null;
    }

    public String getTypeUpper() {
        return type != null ? type.toUpperCase() : null;
    }

    // =====================================================
    // Factory
    // =====================================================

    public static Order market(Long chatId,
                               String symbol,
                               String side,
                               BigDecimal quantity,
                               BigDecimal executionPrice,
                               StrategyType strategyType) {
        Order order = new Order();
        order.chatId = chatId;
        order.symbol = symbol;
        order.side = side != null ? side.toUpperCase() : null;
        order.type = "MARKET";
        order.setQuantity(quantity);
        order.price = executionPrice;
        order.avgPrice = executionPrice;
        order.executedQty = quantity;
        order.status = "FILLED";
        order.filled = true;
        order.time = System.currentTimeMillis();
        order.strategyType = strategyType;
        return order;
    }
}
