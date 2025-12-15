package com.chicu.aitradebot.strategy.scalping;

import com.chicu.aitradebot.common.enums.NetworkType;
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

    // ============================================================
    // 🔹 УНИВЕРСАЛЬНЫЕ ПОЛЯ (единая структура)
    // ============================================================

    @Builder.Default
    private String symbol = "BTCUSDT";

    @Builder.Default
    private String timeframe = "1m";

    @Builder.Default
    @Column(name = "candle_limit", nullable = false)
    private int cachedCandlesLimit = 300;

    @Builder.Default
    @Column(nullable = false)
    private double capitalUsd = 50.0;

    @Builder.Default
    private double commissionPct = 0.04;

    @Builder.Default
    private double riskPerTradePct = 1.0;

    @Builder.Default
    private double dailyLossLimitPct = 5.0;

    @Builder.Default
    private int leverage = 1;

    @Builder.Default
    private boolean reinvestProfit = false;

    @Builder.Default
    private double takeProfitPct = 0.5;

    @Builder.Default
    private double stopLossPct = 0.5;

    // ============================================================
    // 🔸 УНИКАЛЬНЫЕ ПАРАМЕТРЫ SCALPING
    // ============================================================

    @Builder.Default
    private int windowSize = 20;

    @Builder.Default
    private double priceChangeThreshold = 0.3; // %

    @Builder.Default
    private double spreadThreshold = 0.1; // %

    @Builder.Default
    private double orderVolume = 20.0;

    // ============================================================
    // SYSTEM
    // ============================================================

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private NetworkType networkType = NetworkType.TESTNET;

    @Builder.Default
    private boolean active = false;

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @PrePersist
    public void prePersist() {
        if (createdAt == null)
            createdAt = Instant.now();
    }
}
