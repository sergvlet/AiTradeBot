package com.chicu.aitradebot.journal;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "trade_outcome",
        indexes = {
                @Index(name = "ix_outcome_corr", columnList = "correlation_id"),
                @Index(name = "ix_outcome_chat_ex_net_sym", columnList = "chat_id,exchange_name,network_type,symbol")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_outcome_corr", columnNames = {"correlation_id"})
        }
)
public class TradeOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ===== ключ корреляции (склейка intent <-> exec) =====
    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    // ===== идентификация стратегии/среды =====
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", length = 64)
    private StrategyType strategyType;

    @Column(name = "exchange_name", nullable = false, length = 32)
    private String exchangeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "network_type", nullable = false, length = 16)
    private NetworkType networkType;

    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    @Column(name = "timeframe", length = 16)
    private String timeframe;

    // ===== вход/выход (минимум) =====
    @Column(name = "entry_side", length = 8)
    private String entrySide; // BUY/SELL

    @Column(name = "entry_price", precision = 28, scale = 12)
    private BigDecimal entryPrice;

    @Column(name = "entry_qty", precision = 28, scale = 12)
    private BigDecimal entryQty;

    @Column(name = "exit_price", precision = 28, scale = 12)
    private BigDecimal exitPrice;

    @Column(name = "exit_qty", precision = 28, scale = 12)
    private BigDecimal exitQty;

    // ===== pnl/fees (упрощённо) =====
    @Column(name = "pnl_pct", precision = 18, scale = 8)
    private BigDecimal pnlPct;

    @Column(name = "fees_amount", precision = 28, scale = 12)
    private BigDecimal feesAmount;

    @Column(name = "fees_asset", length = 16)
    private String feesAsset;

    // ===== состояние =====
    @Column(name = "status", length = 32)
    private String status; // OPEN/CLOSED/UNKNOWN

    @Column(name = "outcome_type", length = 32)
    private String outcomeType; // TP_FIRST/SL_FIRST/TIMEOUT/MANUAL/UNKNOWN

    // ===== ids для дебага/связи =====
    @Column(name = "entry_client_order_id", length = 128)
    private String entryClientOrderId;

    @Column(name = "exit_client_order_id", length = 128)
    private String exitClientOrderId;

    @Column(name = "entry_exchange_order_id", length = 64)
    private String entryExchangeOrderId;

    @Column(name = "exit_exchange_order_id", length = 64)
    private String exitExchangeOrderId;

    @Column(name = "entry_exchange_trade_id", length = 64)
    private String entryExchangeTradeId;

    @Column(name = "exit_exchange_trade_id", length = 64)
    private String exitExchangeTradeId;

    // ===== время =====
    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
        normalize();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        normalize();
    }

    private void normalize() {
        if (exchangeName != null) exchangeName = exchangeName.trim().toUpperCase();
        if (symbol != null) symbol = symbol.trim().toUpperCase();
        if (entrySide != null) entrySide = entrySide.trim().toUpperCase();
        if (status != null) status = status.trim().toUpperCase();
        if (outcomeType != null) outcomeType = outcomeType.trim().toUpperCase();
        if (correlationId != null) correlationId = correlationId.trim();
    }
}
