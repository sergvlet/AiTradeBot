// src/main/java/com/chicu/aitradebot/strategy/rl/RlAgentSettings.java
package com.chicu.aitradebot.strategy.rl;

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
@Table(
        name = "rl_agent_settings",
        indexes = {
                @Index(name = "ix_rl_agent_settings_chat_id", columnList = "chat_id")
        }
)
public class RlAgentSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    /**
     * ✅ Ключ агента (чтобы выбрать конкретную RL-модель/профиль).
     * RlAgentStrategyV4 ожидает getAgentKey().
     */
    @Column(name = "agent_key", nullable = false)
    private String agentKey;

    /**
     * ✅ Сколько свечей брать на формирование состояния (features/obs).
     * RlAgentStrategyV4 ожидает getLookbackCandles().
     */
    @Column(name = "lookback_candles", nullable = false)
    private Integer lookbackCandles;

    /**
     * Минимальная уверенность, чтобы разрешить BUY/SELL.
     * 0..1
     */
    @Column(name = "min_confidence", precision = 20, scale = 10)
    private BigDecimal minConfidence;

    /**
     * Порог “держать” (если уверенность ниже — HOLD).
     * 0..1
     */
    @Column(name = "hold_threshold", precision = 20, scale = 10)
    private BigDecimal holdThreshold;

    /**
     * Интервал обновления решения RL (чтобы не дергать модель на каждом тике).
     */
    @Column(name = "decision_cooldown_ms")
    private Long decisionCooldownMs;

    /**
     * Включить/выключить RL-агента (отдельно от StrategySettings.active).
     */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private Integer version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;

        // ✅ дефолты
        if (agentKey == null || agentKey.isBlank()) agentKey = "default";
        if (lookbackCandles == null) lookbackCandles = 200;
        if (lookbackCandles < 50) lookbackCandles = 50;
        if (lookbackCandles > 2000) lookbackCandles = 2000;

        if (minConfidence == null) minConfidence = new BigDecimal("0.60");
        if (holdThreshold == null) holdThreshold = new BigDecimal("0.50");
        if (decisionCooldownMs == null) decisionCooldownMs = 1500L;

        // оставляю твоё поведение: всегда включено по умолчанию
        enabled = enabled || true;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();

        // на апдейте тоже подстрахуем
        if (agentKey == null || agentKey.isBlank()) agentKey = "default";
        if (lookbackCandles == null) lookbackCandles = 200;
        if (lookbackCandles < 50) lookbackCandles = 50;
        if (lookbackCandles > 2000) lookbackCandles = 2000;

        if (minConfidence == null) minConfidence = new BigDecimal("0.60");
        if (holdThreshold == null) holdThreshold = new BigDecimal("0.50");
        if (decisionCooldownMs == null) decisionCooldownMs = 1500L;
    }
}
