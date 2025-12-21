package com.chicu.aitradebot.strategy.scalping;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.strategy.core.settings.OrderVolumeProvider;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "scalping_strategy_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScalpingStrategySettings implements OrderVolumeProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long chatId;

    // ============================================================
    // STRATEGY PARAMS
    // ============================================================

    @Builder.Default
    @Column(nullable = false)
    private String symbol = "BTCUSDT";

    @Builder.Default
    @Column(nullable = false)
    private String timeframe = "1m";

    @Builder.Default
    @Column(name = "cached_candles_limit", nullable = false)
    private int cachedCandlesLimit = 300;

    @Builder.Default
    @Column(nullable = false)
    private int windowSize = 20;

    @Builder.Default
    @Column(nullable = false)
    private double priceChangeThreshold = 0.3; // %

    @Builder.Default
    @Column(nullable = false)
    private double spreadThreshold = 0.1; // %

    @Builder.Default
    @Column(nullable = false)
    private double takeProfitPct = 0.5;

    @Builder.Default
    @Column(nullable = false)
    private double stopLossPct = 0.5;

    // ⚠️ ХРАНИМ double
    @Builder.Default
    @Column(nullable = false)
    private double orderVolume = 20.0;

    // ============================================================
    // ✅ КОНТРАКТ v4
    // ============================================================

    @Override
    public BigDecimal getOrderVolume() {
        return BigDecimal.valueOf(orderVolume);
    }

    // ============================================================
    // SYSTEM
    // ============================================================

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private NetworkType networkType = NetworkType.TESTNET;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = false;

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
