package com.chicu.aitradebot.ml.persistence;

import com.chicu.aitradebot.common.enums.StrategyType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "ml_tuning_run",
        indexes = {
                @Index(name = "idx_ml_tuning_run_chat_strategy_created", columnList = "chat_id, strategy_type, created_at DESC")
        }
)
public class TuningRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", nullable = false, length = 64)
    private StrategyType strategyType;

    @Column(name = "symbol", length = 64)
    private String symbol;

    @Column(name = "timeframe", length = 32)
    private String timeframe;

    /**
     * JSON слепок параметров ДО/ПОСЛЕ (именно “что применили”).
     * Формат держим единым на уровне ML-слоя (mapper).
     */
    @Lob
    @Column(name = "old_json")
    private String oldJson;

    @Lob
    @Column(name = "new_json")
    private String newJson;

    @Column(name = "score_before", precision = 38, scale = 18)
    private BigDecimal scoreBefore;

    @Column(name = "score_after", precision = 38, scale = 18)
    private BigDecimal scoreAfter;

    @Column(name = "model_version", length = 128)
    private String modelVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
