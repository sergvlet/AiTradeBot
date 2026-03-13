package com.chicu.aitradebot.ai.ml.dataset;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Locale;

@Entity
@Table(
        name = "ml_samples",
        indexes = {
                @Index(name = "ix_ml_samples_chat_strategy_time", columnList = "chat_id,strategy_type,created_at"),
                @Index(name = "ix_ml_samples_symbol_time", columnList = "symbol,created_at"),
                @Index(name = "ix_ml_samples_ts", columnList = "ts"),
                // ✅ ускоряет findForTraining (chatId + type + symbol + tf + time)
                @Index(name = "ix_ml_samples_train", columnList = "chat_id,strategy_type,symbol,timeframe,created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MlSampleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // routing
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", nullable = false, length = 64)
    private StrategyType strategyType;

    @Column(name = "exchange", length = 32)
    private String exchange;

    @Column(name = "network", length = 32)
    private String network;

    // market identity
    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    @Column(name = "timeframe", length = 16)
    private String timeframe;

    @Column(name = "ts")
    private Instant ts;

    // ML data
    @Column(name = "label", length = 32)
    private String label;

    @Column(name = "target", length = 32)
    private String target;

    @Column(name = "proba")
    private Double proba;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features_json", columnDefinition = "jsonb")
    private JsonNode featuresJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta_json", columnDefinition = "jsonb")
    private JsonNode metaJson;

    // audit
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
        this.exchange = normalizeUpperNullable(this.exchange);
        this.network = normalizeUpperNullable(this.network);
        this.symbol = normalizeUpperNullable(this.symbol);
        this.timeframe = normalizeLowerNullable(this.timeframe);

        this.label = normalizeTrimNullable(this.label);
        this.target = normalizeTrimNullable(this.target);
    }

    private void validate() {
        if (chatId == null) throw new IllegalStateException("MlSampleEntity.chatId is null");
        if (strategyType == null) throw new IllegalStateException("MlSampleEntity.strategyType is null");
        if (symbol == null) throw new IllegalStateException("MlSampleEntity.symbol is null");
    }

    private static String normalizeTrimNullable(String s) {
        if (s == null) return null;
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private static String normalizeUpperNullable(String s) {
        String v = normalizeTrimNullable(s);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    private static String normalizeLowerNullable(String s) {
        String v = normalizeTrimNullable(s);
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }
}
