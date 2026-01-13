// src/main/java/com/chicu/aitradebot/strategy/hybrid/HybridStrategySettings.java
package com.chicu.aitradebot.strategy.hybrid;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "hybrid_strategy_settings")
public class HybridStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long chatId;

    @Column(nullable = false)
    private String mlModelKey;

    @Column(nullable = false)
    private String rlAgentKey;

    @Column(nullable = false)
    private Double minConfidence; // общий порог

    @Column(nullable = false)
    private Boolean allowSingleSourceBuy; // если только один источник BUY, второй HOLD

    @Column(nullable = false)
    private Instant updatedAt;

    // =====================================================
    // ✅ ДОБАВЛЕНО: поля, которые ждёт HybridStrategyV4
    // (ничего не удаляем, только расширяем)
    // =====================================================

    /**
     * Порог ML-сигнала (аналог mlThreshold).
     * Можно хранить 0..1 или 70 (будет нормализовано).
     */
    @Getter
    @Column(nullable = false)
    private Double mlThreshold;

    /**
     * Минимальная уверенность RL (аналог rlMinConfidence).
     * Можно хранить 0..1 или 70 (будет нормализовано).
     */
    @Getter
    @Column(nullable = false)
    private Double rlMinConfidence;

    /**
     * Сколько свечей брать для фичей/контекста (аналог lookbackCandles).
     */
    @Getter
    @Column(nullable = false)
    private Integer lookbackCandles;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();

        if (mlModelKey == null) mlModelKey = "default";
        if (rlAgentKey == null) rlAgentKey = "default";

        if (minConfidence == null) minConfidence = 0.60;
        if (allowSingleSourceBuy == null) allowSingleSourceBuy = Boolean.TRUE;

        // ✅ дефолты для новых полей
        if (mlThreshold == null) mlThreshold = 0.70;
        if (rlMinConfidence == null) rlMinConfidence = 0.55;
        if (lookbackCandles == null) lookbackCandles = 200;

        // ✅ нормализация: если кто-то хранит 70 вместо 0.70
        mlThreshold = normalize01(mlThreshold);
        rlMinConfidence = normalize01(rlMinConfidence);
        minConfidence = normalize01(minConfidence);

        // ✅ пределы для lookback
        if (lookbackCandles < 50) lookbackCandles = 50;
        if (lookbackCandles > 2000) lookbackCandles = 2000;
    }

    private static Double normalize01(Double v) {
        if (v == null) return null;
        double d = v;

        // 70 -> 0.70
        if (d > 1.0) d = d / 100.0;

        if (d < 0.0) d = 0.0;
        if (d > 1.0) d = 1.0;

        return d;
    }

}
