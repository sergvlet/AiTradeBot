// src/main/java/com/chicu/aitradebot/strategy/rl/RlAgentStrategySettings.java
package com.chicu.aitradebot.strategy.rl;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "rl_agent_strategy_settings")
public class RlAgentStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long chatId;

    @Column(nullable = false)
    private String agentKey;     // алиас агента

    @Column(nullable = false)
    private Double minConfidence;

    @Column(nullable = false)
    private Integer decisionIntervalSeconds; // не принимать решения чаще

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
        if (agentKey == null) agentKey = "default";
        if (minConfidence == null) minConfidence = 0.55;
        if (decisionIntervalSeconds == null) decisionIntervalSeconds = 5;
        if (decisionIntervalSeconds < 1) decisionIntervalSeconds = 1;
    }
}
