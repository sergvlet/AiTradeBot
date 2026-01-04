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
        name = "trade_execution_event",
        indexes = {
                @Index(name = "ix_exec_event_uid", columnList = "event_uid"),
                @Index(name = "ix_exec_corr", columnList = "correlation_id"),
                @Index(name = "ix_exec_client_order", columnList = "client_order_id"),
                @Index(name = "ix_exec_order_id", columnList = "exchange_order_id"),
                @Index(name = "ix_exec_trade_id", columnList = "exchange_trade_id"),
                @Index(name = "ix_exec_chat_ex_net_sym", columnList = "chat_id,exchange_name,network_type,symbol")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_exec_event_uid", columnNames = {"event_uid"})
        }
)
public class TradeExecutionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ========== идентификация стратегии/среды ==========
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", length = 64)
    private StrategyType strategyType; // может быть null, если факт пришёл без контекста

    @Column(name = "exchange_name", nullable = false, length = 32)
    private String exchangeName; // BINANCE/BYBIT/...

    @Enumerated(EnumType.STRING)
    @Column(name = "network_type", nullable = false, length = 16)
    private NetworkType networkType; // MAINNET/TESTNET

    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    @Column(name = "timeframe", length = 16)
    private String timeframe;

    // ========== дедуп / корреляция ==========
    @Column(name = "event_uid", nullable = false, length = 128)
    private String eventUid;

    @Column(name = "correlation_id", length = 64)
    private String correlationId; // извлекаем из clientOrderId (или берём из link/intent)

    @Column(name = "client_order_id", length = 128)
    private String clientOrderId;

    @Column(name = "exchange_order_id", length = 64)
    private String exchangeOrderId;

    @Column(name = "exchange_trade_id", length = 64)
    private String exchangeTradeId;

    // ========== тип/статус ==========
    @Column(name = "event_type", length = 32)
    private String eventType;

    @Column(name = "side", length = 8)
    private String side; // BUY/SELL

    @Column(name = "status", length = 32)
    private String status; // NEW/FILLED/PARTIALLY_FILLED/...

    // ========== числа ==========
    @Column(name = "price", precision = 28, scale = 12)
    private BigDecimal price;

    @Column(name = "qty", precision = 28, scale = 12)
    private BigDecimal qty;

    @Column(name = "quote_qty", precision = 28, scale = 12)
    private BigDecimal quoteQty;

    // ========== комиссии ==========
    @Column(name = "fee_asset", length = 16)
    private String feeAsset;

    @Column(name = "fee_amount", precision = 28, scale = 12)
    private BigDecimal feeAmount;

    @Column(name = "maker")
    private Boolean maker;

    // ========== время / raw ==========
    @Column(name = "event_time")
    private Instant eventTime;

    @Lob
    @Column(name = "raw_json", columnDefinition = "text")
    private String rawJson;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (exchangeName != null) exchangeName = exchangeName.trim().toUpperCase();
        if (symbol != null) symbol = symbol.trim().toUpperCase();
        if (clientOrderId != null) clientOrderId = clientOrderId.trim();
        if (eventUid != null) eventUid = eventUid.trim();
        if (correlationId != null) correlationId = correlationId.trim();
    }
}
