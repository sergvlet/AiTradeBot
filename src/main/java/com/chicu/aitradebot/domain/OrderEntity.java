package com.chicu.aitradebot.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "orders",
        indexes = {
                @Index(name = "idx_orders_chat_symbol", columnList = "chat_id,symbol"),
                @Index(name = "idx_orders_chat_strategy", columnList = "chat_id,strategy_type"),
                @Index(name = "idx_orders_status", columnList = "status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** chatId — идентификатор пользователя */
    @Column(name = "chat_id", nullable = false)
    private Long chatId;


    /** старое поле, оставлено для миграции */
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 50)
    private String symbol;

    /** BUY / SELL */
    @Column(nullable = false, length = 10)
    private String side;

    @Column(nullable = false, precision = 28, scale = 12)
    private BigDecimal price;

    @Column(nullable = false, precision = 28, scale = 12)
    private BigDecimal quantity;

    /** price * quantity */
    @Column(nullable = false, precision = 28, scale = 12)
    private BigDecimal total;

    /** SMART_FUSION / SCALPING / ML_INVEST */
    @Column(name = "strategy_type", nullable = false, length = 64)
    private String strategyType;

    /** NEW / OPEN / FILLED / CANCELED / PARTIALLY_FILLED */
    @Column(nullable = false, length = 32)
    private String status;

    /** обязательная колонка в БД */
    @Column(name = "filled", nullable = false)
    private Boolean filled = true;

    /** timestamp в миллисекундах */
    @Column(name = "timestamp")
    private Long timestamp;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;


    // ============================================
    // ULTRA-поля (TP/SL, ML, причины, PnL)
    // ============================================

    @Column(name = "entry_reason", length = 255)
    private String entryReason;

    @Column(name = "exit_reason", length = 255)
    private String exitReason;

    @Column(name = "tp_price", precision = 28, scale = 12)
    private BigDecimal takeProfitPrice;

    @Column(name = "sl_price", precision = 28, scale = 12)
    private BigDecimal stopLossPrice;

    @Column(name = "exit_price", precision = 28, scale = 12)
    private BigDecimal exitPrice;

    @Column(name = "exit_timestamp")
    private Long exitTimestamp;

    @Column(name = "realized_pnl_usd", precision = 28, scale = 12)
    private BigDecimal realizedPnlUsd;

    @Column(name = "realized_pnl_pct", precision = 10, scale = 4)
    private BigDecimal realizedPnlPct;

    @Column(name = "tp_hit")
    private Boolean tpHit;

    @Column(name = "sl_hit")
    private Boolean slHit;

    @Column(name = "ml_confidence", precision = 10, scale = 5)
    private BigDecimal mlConfidence;


    // ============================================
    // Lifecycle
    // ============================================
    @PrePersist
    public void prePersist() {

        if (createdAt == null)
            createdAt = LocalDateTime.now();

        if (timestamp == null)
            timestamp = System.currentTimeMillis();

        if (price != null && quantity != null && total == null)
            total = price.multiply(quantity);

        if (chatId == null && userId != null)
            chatId = userId;

        if (filled == null)
            filled = true;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();

        if (price != null && quantity != null)
            total = price.multiply(quantity);

        if (filled == null)
            filled = true;
    }
}
