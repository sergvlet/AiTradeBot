package com.chicu.aitradebot.ai.persistence;

import com.chicu.aitradebot.common.enums.StrategyType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "ml_model_artifact",
        uniqueConstraints = @UniqueConstraint(name = "uq_ml_model_artifact", columnNames = {"strategy_type", "version"}),
        indexes = {
                @Index(name = "idx_ml_model_artifact_strategy_created", columnList = "strategy_type, created_at DESC")
        }
)
public class ModelArtifactEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", nullable = false, length = 64)
    private StrategyType strategyType;

    /**
     * Версия артефакта (например: "scalping-2026-01-03T12-30-00Z").
     * Строка, чтобы не хардкодить формат в БД.
     */
    @Column(name = "version", nullable = false, length = 128)
    private String version;

    /**
     * Путь к файлу модели (локально/volume). Конкретная реализация хранения — позже.
     */
    @Lob
    @Column(name = "path", nullable = false)
    private String path;

    /**
     * Доп. метаданные JSON: параметры обучения, фичи, размеры датасета, seed и т.д.
     */
    @Lob
    @Column(name = "meta_json")
    private String metaJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
