package com.chicu.aitradebot.strategy.scalping;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "scalping_strategy_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScalpingStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long chatId;

    // =========================
    // LOGIC PARAMS
    // =========================

    @Builder.Default
    @Column(nullable = false)
    private Integer windowSize = 20;

    @Builder.Default
    @Column(nullable = false)
    private Double priceChangeThreshold = 0.3;

    @Builder.Default
    @Column(nullable = false)
    private Double spreadThreshold = 0.1;


    // =========================
    // AUDIT
    // =========================

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = true)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
