package com.chicu.aitradebot.ai.ml.artifacts;

import com.chicu.aitradebot.common.enums.StrategyType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "ml_model_artifacts",
        indexes = {
                @Index(name = "ix_ml_art_chat_type", columnList = "chat_id,strategy_type"),
                @Index(name = "ix_ml_art_model_key", columnList = "model_key")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MlModelArtifactEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", nullable = false, length = 32)
    private StrategyType strategyType;

    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    @Column(name = "timeframe", nullable = false, length = 16)
    private String timeframe;

    @Column(name = "schema_hash", length = 80)
    private String schemaHash;

    @Column(name = "model_key", nullable = false, length = 160)
    private String modelKey;

    @Column(name = "model_version", nullable = false, length = 80)
    private String modelVersion;

    @Column(name = "metrics_json")
    private String metricsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
