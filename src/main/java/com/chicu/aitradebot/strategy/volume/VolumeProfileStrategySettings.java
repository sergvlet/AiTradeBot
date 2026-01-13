// src/main/java/com/chicu/aitradebot/strategy/volume/VolumeProfileStrategySettings.java
package com.chicu.aitradebot.strategy.volume;

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
@Table(name = "volume_profile_strategy_settings")
public class VolumeProfileStrategySettings {

    public enum EntryMode {
        MEAN_REVERT,
        BREAKOUT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long chatId;

    /**
     * Сколько свечей брать для построения профиля.
     * Если null — берем min(StrategySettings.cachedCandlesLimit, 300).
     */
    @Column(nullable = true)
    private Integer lookbackCandles;

    /**
     * Кол-во "корзин" по цене (гистограмма профиля).
     */
    @Column(nullable = false)
    private Integer bins;

    /**
     * Value Area (например 70%).
     * Храним как проценты: 70 = 70%.
     */
    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal valueAreaPct;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryMode entryMode;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Integer version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;

        if (bins == null) bins = 48;
        if (valueAreaPct == null) valueAreaPct = new BigDecimal("70"); // 70%
        if (entryMode == null) entryMode = EntryMode.MEAN_REVERT;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        if (bins == null) bins = 48;
        if (valueAreaPct == null) valueAreaPct = new BigDecimal("70");
        if (entryMode == null) entryMode = EntryMode.MEAN_REVERT;
    }
}
