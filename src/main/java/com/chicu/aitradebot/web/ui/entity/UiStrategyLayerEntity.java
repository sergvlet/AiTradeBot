package com.chicu.aitradebot.web.ui.entity;

import com.chicu.aitradebot.common.enums.StrategyType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Locale;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
        name = "ui_strategy_layers",
        indexes = {
                // общий доступ по контексту (график/чтение)
                @Index(
                        name = "idx_ui_layer_ctx",
                        columnList = "chat_id, strategy_type, symbol"
                ),
                // для findLatestByType + deleteByType (самое важное)
                @Index(
                        name = "idx_ui_layer_latest",
                        columnList = "chat_id, strategy_type, symbol, layer_type, created_at"
                ),
                @Index(name = "idx_ui_layer_time", columnList = "candle_time"),
                @Index(name = "idx_ui_layer_created", columnList = "created_at")
        }
)
public class UiStrategyLayerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // =====================================================
    // CONTEXT
    // =====================================================

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", nullable = false, length = 32)
    private StrategyType strategyType;

    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    // =====================================================
    // TYPE
    // =====================================================
    /**
     * LEVELS | ZONE | ORDERS | TPSL | BUYSELL_ZONES | ...
     */
    @Column(name = "layer_type", nullable = false, length = 32)
    private String layerType;

    // =====================================================
    // DATA (JSON)
    // =====================================================
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    // =====================================================
    // TIME BINDING
    // =====================================================

    @Column(name = "candle_time", nullable = false)
    private Instant candleTime;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // =====================================================
    // LIFECYCLE
    // =====================================================

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();

        if (this.createdAt == null) this.createdAt = now;
        if (this.candleTime == null) this.candleTime = now;

        if (this.symbol != null) {
            String s = this.symbol.trim().toUpperCase(Locale.ROOT);
            this.symbol = s.isEmpty() ? null : s;
        }

        if (this.layerType != null) {
            String t = this.layerType.trim().toUpperCase(Locale.ROOT);
            this.layerType = t.isEmpty() ? null : t;
        }
    }
}