package com.chicu.aitradebot.ai.ml.artifacts;

import com.chicu.aitradebot.common.enums.StrategyType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Locale;

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

    /**
     * В БД это TEXT, НЕ OID/CLOB.
     * Явно закрепляем тип, чтобы Hibernate не ожидал CLOB (oid) при schema-validation.
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "metrics_json", columnDefinition = "text")
    private String metricsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        normalize();
        validate();
    }

    @PreUpdate
    protected void preUpdate() {
        normalize();
        validate();
    }

    private void normalize() {
        this.symbol = normUpper(this.symbol);
        this.timeframe = normLower(this.timeframe);
        this.schemaHash = normTrim(this.schemaHash);
        this.modelKey = normTrim(this.modelKey);
        this.modelVersion = normTrim(this.modelVersion);
        this.metricsJson = normTrim(this.metricsJson);
    }

    private void validate() {
        if (chatId == null) throw new IllegalStateException("MlModelArtifactEntity.chatId is null");
        if (strategyType == null) throw new IllegalStateException("MlModelArtifactEntity.strategyType is null");
        if (symbol == null) throw new IllegalStateException("MlModelArtifactEntity.symbol is null");
        if (timeframe == null) throw new IllegalStateException("MlModelArtifactEntity.timeframe is null");
        if (modelKey == null) throw new IllegalStateException("MlModelArtifactEntity.modelKey is null");
        if (modelVersion == null) throw new IllegalStateException("MlModelArtifactEntity.modelVersion is null");
    }

    private static String normTrim(String s) {
        if (s == null) return null;
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private static String normUpper(String s) {
        String v = normTrim(s);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    private static String normLower(String s) {
        String v = normTrim(s);
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }
}