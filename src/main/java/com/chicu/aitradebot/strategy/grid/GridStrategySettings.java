package com.chicu.aitradebot.strategy.grid;

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
        name = "grid_strategy_settings",
        indexes = @Index(name = "ix_grid_strategy_settings_chat_id", columnList = "chat_id")
)
public class GridStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    // ✅ ТОЛЬКО УНИКАЛЬНЫЕ ПАРАМЕТРЫ GRID
    @Column(name = "grid_levels", nullable = false)
    @Builder.Default
    private Integer gridLevels = 10;

    @Column(name = "grid_step_pct", nullable = false, precision = 18, scale = 8)
    @Builder.Default
    private BigDecimal gridStepPct = new BigDecimal("0.50000000");

    @Column(name = "order_volume", nullable = false, precision = 18, scale = 8)
    @Builder.Default
    private BigDecimal orderVolume = new BigDecimal("20.00000000");

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
