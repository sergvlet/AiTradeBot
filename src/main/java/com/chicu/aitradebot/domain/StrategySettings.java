package com.chicu.aitradebot.domain;

import com.chicu.aitradebot.common.enums.StrategyType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "strategy_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StrategyType type;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String timeframe;

    @Builder.Default
    private Integer cachedCandlesLimit = 500;

    /** Проценты/доли — BigDecimal с точной шкалой */
    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal takeProfitPct = BigDecimal.valueOf(1.0); // 1.0%

    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal stopLossPct = BigDecimal.valueOf(1.0);   // 1.0%

    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal commissionPct = BigDecimal.valueOf(0.2); // 0.2%

    @Builder.Default
    private int leverage = 1;

    @Builder.Default
    private int version = 1;

    @Builder.Default
    private boolean active = true;

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }


    /** Совместимость со старым кодом: заменить getStrategyType() -> возвращает поле type */
    @Transient
    public StrategyType getStrategyType() {
        return this.type;
    }

    /** Удобочитаемое имя из enum (для UI), чтобы не хранить его в БД */
    @Transient
    public String getStrategyName() {
        return (this.type != null) ? this.type.name().replace('_', ' ') : "Unknown";
    }
}
