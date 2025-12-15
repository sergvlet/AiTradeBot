package com.chicu.aitradebot.web.ui.entity;

import com.chicu.aitradebot.common.enums.StrategyType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
        name = "ui_strategy_layers",
        indexes = {
                @Index(
                        name = "idx_ui_layer_ctx",
                        columnList = "chatId, strategyType, symbol"
                ),
                @Index(name = "idx_ui_layer_time", columnList = "candleTime"),
                @Index(name = "idx_ui_layer_created", columnList = "createdAt")
        }
)
public class UiStrategyLayerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // =====================================================
    // CONTEXT
    // =====================================================
    @Column(nullable = false)
    private Long chatId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StrategyType strategyType;

    @Column(nullable = false, length = 32)
    private String symbol;

    // =====================================================
    // TYPE
    // =====================================================
    /**
     * LEVELS | ZONE | LINE | SIGNAL
     */
    @Column(nullable = false, length = 32)
    private String layerType;

    // =====================================================
    // DATA (JSON)
    // =====================================================
    /**
     * JSON:
     *  - LEVELS: { "levels": [88000, 88500] }
     *  - ZONE:   { "top": 90200, "bottom": 88000, "color": "rgba(...)" }
     *  - LINE:   { "price": 89123.4, "color": "#fff" }
     */
    @JdbcTypeCode(SqlTypes.JSON) // 🔥 Используем аннотацию для jsonb
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    // =====================================================
    // TIME BINDING
    // =====================================================
    /**
     * К какой свече относится слой
     */
    @Column(nullable = false)
    private Instant candleTime;

    @Column(nullable = false)
    private Instant createdAt;

    // =====================================================
    // LIFECYCLE
    // =====================================================
    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
