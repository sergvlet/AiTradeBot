package com.chicu.aitradebot.domain;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.StrategyPositionSource;
import com.chicu.aitradebot.domain.enums.StrategyPositionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "strategy_positions",
        indexes = {
                @Index(name = "ix_strategy_pos_ctx", columnList = "chat_id,strategy_type,exchange_name,network_type,symbol"),
                @Index(name = "ix_strategy_pos_status", columnList = "status"),
                @Index(name = "ux_strategy_pos_uid", columnList = "position_uid", unique = true)
        }
)
public class StrategyPositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "position_uid", nullable = false, length = 80)
    private String positionUid;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private StrategyPositionStatus status = StrategyPositionStatus.OPEN;

    @Column(name = "side", nullable = false, length = 8)
    @Builder.Default
    private String side = "BUY";

    @Column(name = "qty", precision = 28, scale = 12)
    private BigDecimal qty;

    @Column(name = "avg_entry_price", precision = 28, scale = 12)
    private BigDecimal avgEntryPrice;

    @Column(name = "quote_spent", precision = 28, scale = 12)
    private BigDecimal quoteSpent;

    @Column(name = "tp_price", precision = 28, scale = 12)
    private BigDecimal tpPrice;

    @Column(name = "sl_price", precision = 28, scale = 12)
    private BigDecimal slPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 32)
    @Builder.Default
    private StrategyPositionSource source = StrategyPositionSource.LOCAL;

    @Column(name = "entry_order_id")
    private Long entryOrderId;

    @Column(name = "exit_order_id")
    private Long exitOrderId;

    @Column(name = "entry_client_order_id", length = 128)
    private String entryClientOrderId;

    @Column(name = "exit_client_order_id", length = 128)
    private String exitClientOrderId;

    @Column(name = "entry_exchange_order_id", length = 128)
    private String entryExchangeOrderId;

    @Column(name = "exit_exchange_order_id", length = 128)
    private String exitExchangeOrderId;

    @Column(name = "last_exchange_sync_at")
    private Instant lastExchangeSyncAt;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Integer version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (positionUid == null || positionUid.isBlank()) {
            positionUid = UUID.randomUUID().toString().replace("-", "");
        }
        if (openedAt == null) openedAt = now;
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        normalize();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        normalize();
    }

    private void normalize() {
        exchangeName = upper(exchangeName);
        symbol = upper(symbol);
        side = upper(side);
        entryClientOrderId = trim(entryClientOrderId);
        exitClientOrderId = trim(exitClientOrderId);
        entryExchangeOrderId = trim(entryExchangeOrderId);
        exitExchangeOrderId = trim(exitExchangeOrderId);
        if (side == null) side = "BUY";
        if (status == null) status = StrategyPositionStatus.OPEN;
        if (source == null) source = StrategyPositionSource.LOCAL;
    }

    private static String upper(String v) {
        if (v == null) return null;
        String s = v.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String trim(String v) {
        if (v == null) return null;
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }
}
