// src/main/java/com/chicu/aitradebot/strategy/smartfusion/SmartFusionStrategySettings.java
package com.chicu.aitradebot.strategy.smartfusion;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "smart_fusion_strategy_settings",
        indexes = {
                @Index(name = "idx_smart_fusion_chat_id", columnList = "chatId")
        })
public class SmartFusionStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long chatId;

    /**
     * Веса “источников” в итоговом решении.
     * Итоговый score = wTech*tech + wMl*ml + wRl*rl
     */
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal weightTech;

    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal weightMl;

    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal weightRl;

    /**
     * Порог для BUY по итоговому score [0..1]
     */
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal decisionThreshold;

    /**
     * Минимальная уверенность ML/RL, иначе их вклад = 0
     */
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal minSourceConfidence;

    /**
     * Параметры “Tech” (простые и стабильные: RSI + EMA)
     */
    @Column(nullable = false)
    private Integer rsiPeriod;

    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal rsiBuyBelow;

    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal rsiSellAbove;

    @Column(nullable = false)
    private Integer emaFast;

    @Column(nullable = false)
    private Integer emaSlow;

    /**
     * Источники ML/RL (ключи), если интеграции ещё нет — можно оставить default.
     */
    @Column(nullable = false)
    private String mlModelKey;

    @Column(nullable = false)
    private String rlAgentKey;

    /**
     * Сколько свечей брать
     */
    @Column(nullable = false)
    private Integer lookbackCandles;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();

        if (weightTech == null) weightTech = new BigDecimal("0.50");
        if (weightMl == null) weightMl = new BigDecimal("0.25");
        if (weightRl == null) weightRl = new BigDecimal("0.25");

        if (decisionThreshold == null) decisionThreshold = new BigDecimal("0.65");
        if (minSourceConfidence == null) minSourceConfidence = new BigDecimal("0.55");

        if (rsiPeriod == null) rsiPeriod = 14;
        if (rsiBuyBelow == null) rsiBuyBelow = new BigDecimal("35");
        if (rsiSellAbove == null) rsiSellAbove = new BigDecimal("65");

        if (emaFast == null) emaFast = 9;
        if (emaSlow == null) emaSlow = 21;

        if (mlModelKey == null) mlModelKey = "default";
        if (rlAgentKey == null) rlAgentKey = "default";

        if (lookbackCandles == null) lookbackCandles = 250;
        if (lookbackCandles < 80) lookbackCandles = 80;
    }
}
