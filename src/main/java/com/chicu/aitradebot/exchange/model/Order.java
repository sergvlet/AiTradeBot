package com.chicu.aitradebot.exchange.model;

import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.*;

import java.math.BigDecimal;

/**
 * 💹 DTO ордера — используется для обмена между стратегиями, API и графиком.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    // ===== Идентификаторы =====
    /** id ордера в локальной БД (если нужно) */
    private Long id;

    /** id ордера на бирже */
    private String orderId;

    /** chatId пользователя (нужен OrderServiceImpl) */
    private Long chatId;

    // ===== Основные поля ордера =====
    /** Символ, например BTCUSDT */
    private String symbol;

    /** BUY / SELL */
    private String side;

    /** MARKET / LIMIT */
    private String type;

    /**
     * Количество (старое поле).
     * Оставлено для совместимости с существующим кодом.
     */
    @Deprecated
    private BigDecimal qty;

    /**
     * Количество (новое поле, под которое заточен OrderServiceImpl: setQuantity/getQuantity).
     */
    private BigDecimal quantity;

    /** Цена */
    private BigDecimal price;

    /** Статус: NEW / FILLED / CANCELED и т.п. */
    private String status;

    /**
     * Время (старое поле, мс).
     * Оставлено для совместимости.
     */
    @Deprecated
    private Long timestamp;

    /**
     * Время (новое поле, под которое заточен OrderServiceImpl: setTime/getTime).
     */
    private Long time;

    /** Исполнен ли ордер полностью */
    private boolean filled;

    /** Стратегия, которая создала ордер (SMART_FUSION, SCALPING, ML_INVEST и т.п.) */
    private StrategyType strategyType;


    // ===== Синхронизация старых / новых полей =====

    /**
     * Геттер quantity, который использует qty, если quantity == null.
     * Нужен для корректной работы старого и нового кода одновременно.
     */
    public BigDecimal getQuantity() {
        return quantity != null ? quantity : qty;
    }

    /**
     * Сеттер quantity — синхронизирует и quantity, и qty.
     */
    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
        this.qty = quantity;
    }

    /**
     * Геттер time, который использует timestamp, если time == null.
     */
    public Long getTime() {
        return time != null ? time : timestamp;
    }

    /**
     * Сеттер time — синхронизирует и time, и timestamp.
     */
    public void setTime(Long time) {
        this.time = time;
        this.timestamp = time;
    }
}
