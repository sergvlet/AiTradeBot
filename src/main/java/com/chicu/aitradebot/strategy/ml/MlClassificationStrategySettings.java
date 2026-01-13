// src/main/java/com/chicu/aitradebot/strategy/ml/MlClassificationStrategySettings.java
package com.chicu.aitradebot.strategy.ml;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ml_classification_strategy_settings")
public class MlClassificationStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long chatId;

    /**
     * Имя модели/алиас, который понимает твой ML слой (python/Java inference).
     * Пример: "xgb_v1", "mlc_ethusdt_1m"
     */
    @Column(nullable = false)
    private String modelKey;

    /**
     * Порог уверенности, чтобы входить в сделку.
     * 0.55..0.95
     */
    @Column(nullable = false)
    private Double minConfidence;

    /**
     * Сколько свечей брать из CandleProvider (если надо для фичей).
     */
    @Column(nullable = false)
    private Integer lookbackCandles;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
        if (modelKey == null) modelKey = "default";
        if (minConfidence == null) minConfidence = 0.60;
        if (lookbackCandles == null) lookbackCandles = 200;
    }
}
