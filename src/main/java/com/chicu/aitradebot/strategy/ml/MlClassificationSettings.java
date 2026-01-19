// src/main/java/com/chicu/aitradebot/strategy/ai/MlClassificationSettings.java
package com.chicu.aitradebot.strategy.ml;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ml_classification_settings",
        indexes = {
                @Index(name = "idx_ml_cls_chat_id", columnList = "chatId")
        })
public class MlClassificationSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long chatId;

    /**
     * Ключ модели (как ты потом свяжешь с Python/XGBoost/registry).
     */
    @Column(nullable = false)
    private String modelKey;

    /**
     * Порог BUY вероятности [0..1] (или если кто-то хранит 70 — нормализуем).
     */
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal buyThreshold;

    /**
     * Порог SELL вероятности (опционально).
     * Если null — SELL сигнал не используем, выход только по TP/SL.
     */
    @Column(precision = 10, scale = 6)
    private BigDecimal sellThreshold;

    /**
     * Минимальная уверенность модели, иначе считаем сигналом HOLD.
     */
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal minConfidence;

    /**
     * Сколько свечей на фичи.
     */
    @Column(nullable = false)
    private Integer lookbackCandles;

    /**
     * Разрешить ли реальный вход в сделку (можно отключать, оставляя только сигналы).
     */
    @Column(nullable = false)
    private boolean tradingEnabled;

    @Column(nullable = false)
    private Instant updatedAt;

    // =====================================================
    // ✅ ДОБАВЛЕНО (ничего не удаляем):
    // Стратегия вызывает getDecisionThreshold() — даём alias к buyThreshold.
    // Плюс нормализация 70 -> 0.70.
    // =====================================================

    /**
     * Alias для совместимости со стратегией (decisionThreshold = buyThreshold).
     */
    public BigDecimal getDecisionThreshold() {
        return normalize01(buyThreshold);
    }

    /**
     * Если где-то задают decisionThreshold — сохраняем в buyThreshold.
     */
    public void setDecisionThreshold(BigDecimal v) {
        this.buyThreshold = normalize01(v);
    }

    // =====================================================
    // JPA hooks
    // =====================================================

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();

        if (modelKey == null || modelKey.isBlank()) modelKey = "xgb_default";

        if (buyThreshold == null) buyThreshold = new BigDecimal("0.70");
        if (minConfidence == null) minConfidence = new BigDecimal("0.55");

        // ✅ нормализация (на случай, если в БД/UI вводят 70 вместо 0.70)
        buyThreshold = normalize01(buyThreshold);
        minConfidence = normalize01(minConfidence);
        if (sellThreshold != null) sellThreshold = normalize01(sellThreshold);

        if (lookbackCandles == null) lookbackCandles = 200;
        if (lookbackCandles < 50) lookbackCandles = 50;
        if (lookbackCandles > 2000) lookbackCandles = 2000;

        // дефолт при первом создании (если объект новый и никто не трогал)
        if (id == null) tradingEnabled = true;
    }

    // =====================================================
    // Utils
    // =====================================================

    private static BigDecimal normalize01(BigDecimal v) {
        if (v == null) return null;

        BigDecimal d = v;

        // 70 -> 0.70
        if (d.compareTo(BigDecimal.ONE) > 0) {
            d = d.divide(new BigDecimal("100"), 18, RoundingMode.HALF_UP);
        }

        if (d.compareTo(BigDecimal.ZERO) < 0) d = BigDecimal.ZERO;
        if (d.compareTo(BigDecimal.ONE) > 0) d = BigDecimal.ONE;

        // под scale=6 как в колонках
        return d.setScale(6, RoundingMode.HALF_UP);
    }
}
