package com.chicu.aitradebot.ai.override;

import com.chicu.aitradebot.common.enums.StrategyType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "ai_override",
        indexes = {
                @Index(name = "ix_ai_override_chat_strategy_active", columnList = "chat_id,strategy_type,active,created_at")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiOverrideEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", nullable = false, length = 64)
    private StrategyType strategyType;

    /**
     * JSON patch: {"windowSize": 18, "priceChangeThreshold": 0.35, ...}
     */
    @Lob
    @Column(name = "patch_json", nullable = false)
    private String patchJson;

    @Column(name = "source", nullable = false, length = 32)
    private String source; // AUTO | SHADOW | MANUAL

    @Column(name = "reason", length = 256)
    private String reason;

    @Column(name = "model_version", length = 64)
    private String modelVersion;

    @Column(name = "confidence")
    private Double confidence;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "active", nullable = false)
    private boolean active;
}
