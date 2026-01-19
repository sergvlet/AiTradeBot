package com.chicu.aitradebot.strategy.vwap;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "vwap_strategy_settings")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class VwapStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long chatId;

    /** Сколько свечей брать для VWAP */
    @Column(nullable = false)
    private Integer windowCandles;

    /** Вход, если цена ниже VWAP на X% */
    @Column(nullable = false)
    private Double entryDeviationPct;

    /** Выход, если цена выше VWAP на X% (мягкий выход) */
    @Column(nullable = false)
    private Double exitDeviationPct;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = true)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (windowCandles == null) windowCandles = 50;
        if (entryDeviationPct == null) entryDeviationPct = 0.30; // 0.30%
        if (exitDeviationPct == null) exitDeviationPct = 0.20;   // 0.20%
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }
}
