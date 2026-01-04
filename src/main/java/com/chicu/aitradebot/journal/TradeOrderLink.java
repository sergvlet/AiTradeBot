package com.chicu.aitradebot.journal;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "trade_order_link",
        indexes = {
                @Index(name = "ix_link_corr", columnList = "correlation_id"),
                @Index(name = "ix_link_client", columnList = "client_order_id"),
                @Index(name = "ix_link_chat_type_ex_net", columnList = "chat_id,strategy_type,exchange_name,network_type")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_link_client", columnNames = {"client_order_id"})
        }
)
public class TradeOrderLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // идентификация стратегии
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", nullable = false, length = 64)
    private StrategyType strategyType;

    @Column(name = "exchange_name", nullable = false, length = 32)
    private String exchangeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "network_type", nullable = false, length = 16)
    private NetworkType networkType;

    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    @Column(name = "timeframe", nullable = false, length = 16)
    private String timeframe;

    // главное: корреляция
    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    @Column(name = "client_order_id", nullable = false, length = 64)
    private String clientOrderId;

    /**
     * Роль ордера в цикле.
     * ENTRY / TP / SL / EXIT / OCO / UNKNOWN
     */
    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (exchangeName != null) exchangeName = exchangeName.trim().toUpperCase();
        if (symbol != null) symbol = symbol.trim().toUpperCase();
        if (timeframe != null) timeframe = timeframe.trim();
        if (correlationId != null) correlationId = correlationId.trim();
        if (clientOrderId != null) clientOrderId = clientOrderId.trim();
        if (role != null) role = role.trim().toUpperCase();
    }
}
