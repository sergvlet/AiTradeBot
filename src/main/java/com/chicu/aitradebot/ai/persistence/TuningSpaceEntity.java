package com.chicu.aitradebot.ai.persistence;

import com.chicu.aitradebot.common.enums.StrategyType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "ml_tuning_space",
        uniqueConstraints = @UniqueConstraint(name = "uq_ml_tuning_space", columnNames = {"strategy_type", "param_name"}),
        indexes = {
                @Index(name = "idx_ml_tuning_space_strategy_enabled", columnList = "strategy_type, enabled")
        }
)
public class TuningSpaceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", nullable = false, length = 64)
    private StrategyType strategyType;

    @Column(name = "param_name", nullable = false, length = 128)
    private String paramName;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 32)
    private ParamValueType valueType;

    // Для BOOLEAN/STRING эти поля могут быть null (это нормально)
    @Column(name = "min_value", precision = 38, scale = 18)
    private BigDecimal minValue;

    @Column(name = "max_value", precision = 38, scale = 18)
    private BigDecimal maxValue;

    @Column(name = "step_value", precision = 38, scale = 18)
    private BigDecimal stepValue;

    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // --- helpers (не хардкод диапазонов, только безопасные проверки) ---

    public String normalizedParamName() {
        return paramName == null ? null : paramName.trim();
    }
}
