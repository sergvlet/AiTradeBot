package com.chicu.aitradebot.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

@Entity
@Table(
        name = "orders",
        indexes = {
                @Index(name = "idx_orders_chat_symbol", columnList = "chat_id,symbol"),
                @Index(name = "idx_orders_chat_strategy", columnList = "chat_id,strategy_type"),
                @Index(name = "idx_orders_status", columnList = "status"),
                @Index(name = "idx_orders_ctx_runtime", columnList = "chat_id,strategy_type,symbol,exchange_name,network_type,timestamp"),
                @Index(name = "idx_orders_position_uid", columnList = "position_uid"),
                @Index(name = "idx_orders_client_order", columnList = "client_order_id"),
                @Index(name = "idx_orders_exchange_order", columnList = "exchange_order_id")
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

    @Column(name = "position_uid", length = 80)
    private String positionUid;

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

    /** MARKET / LIMIT / OCO */
    @Column(name = "order_type", length = 16)
    private String orderType;

    /** ENTRY / EXIT / TP / SL / MANUAL_CLOSE / RESTORE */
    @Column(name = "intent", length = 24)
    private String intent;

    @Column(nullable = false, precision = 28, scale = 12)
    private BigDecimal price;

    @Column(nullable = false, precision = 28, scale = 12)
    private BigDecimal quantity;

    /** price * quantity */
    @Column(nullable = false, precision = 28, scale = 12)
    private BigDecimal total;

    /** SMART_FUSION / SCALPING / ML_INVEST / WINDOW_SCALPING */
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

    /** новый контекст для восстановления после рестарта */
    @Column(name = "exchange_name", length = 32)
    private String exchangeName;

    /** MAINNET / TESTNET */
    @Column(name = "network_type", length = 32)
    private String networkType;

    @Column(name = "client_order_id", length = 128)
    private String clientOrderId;

    @Column(name = "exchange_order_id", length = 128)
    private String exchangeOrderId;

    @Column(name = "exchange_status", length = 64)
    private String exchangeStatus;

    @Column(name = "requested_qty", precision = 28, scale = 12)
    private BigDecimal requestedQty;

    @Column(name = "requested_price", precision = 28, scale = 12)
    private BigDecimal requestedPrice;

    @Column(name = "executed_qty", precision = 28, scale = 12)
    private BigDecimal executedQty;

    @Column(name = "executed_quote_qty", precision = 28, scale = 12)
    private BigDecimal executedQuoteQty;

    @Column(name = "avg_executed_price", precision = 28, scale = 12)
    private BigDecimal avgExecutedPrice;

    @Column(name = "fee_total", precision = 28, scale = 12)
    private BigDecimal feeTotal;

    @Column(name = "fee_asset", length = 16)
    private String feeAsset;

    @Column(name = "parent_order_id")
    private Long parentOrderId;

    @Column(name = "is_reduce_only")
    private Boolean reduceOnly;

    @Column(name = "is_close_order")
    private Boolean closeOrder;

    @Column(name = "source", length = 24)
    private String source;

    @Column(name = "correlation_id", length = 80)
    private String correlationId;

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

    @Column(name = "reject_code", length = 64)
    private String rejectCode;

    @Column(name = "reject_message", length = 512)
    private String rejectMessage;

    // ============================================
    // Lifecycle
    // ============================================

    @PrePersist
    public void prePersist() {
        if (chatId == null && userId != null) {
            chatId = userId;
        }

        normalizeContext();

        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }

        if (timestamp == null) {
            timestamp = System.currentTimeMillis();
        }

        if (price != null && quantity != null && total == null) {
            total = price.multiply(quantity);
        }

        if (filled == null) {
            filled = true;
        }
    }

    @PreUpdate
    public void preUpdate() {
        normalizeContext();
        updatedAt = LocalDateTime.now();

        if (price != null && quantity != null) {
            total = price.multiply(quantity);
        }

        if (filled == null) {
            filled = true;
        }
    }

    private void normalizeContext() {
        symbol = normalizeUpperOrNull(symbol);
        side = normalizeUpperOrNull(side);
        orderType = normalizeUpperOrNull(orderType);
        intent = normalizeUpperOrNull(intent);
        status = normalizeUpperOrNull(status);
        strategyType = normalizeUpperOrNull(strategyType);
        exchangeName = normalizeUpperOrNull(exchangeName);
        networkType = normalizeUpperOrNull(networkType);
        source = normalizeUpperOrNull(source);
        exchangeStatus = normalizeUpperOrNull(exchangeStatus);
        feeAsset = normalizeUpperOrNull(feeAsset);
        clientOrderId = normalizeTrimOrNull(clientOrderId);
        exchangeOrderId = normalizeTrimOrNull(exchangeOrderId);
        positionUid = normalizeTrimOrNull(positionUid);
        correlationId = normalizeTrimOrNull(correlationId);
        rejectCode = normalizeUpperOrNull(rejectCode);
        rejectMessage = normalizeTrimOrNull(rejectMessage);
    }

    private static String normalizeUpperOrNull(String value) {
        if (value == null) return null;
        String v = value.trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    private static String normalizeTrimOrNull(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }
}
