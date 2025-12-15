package com.chicu.aitradebot.strategy.rsie;

import com.chicu.aitradebot.common.enums.NetworkType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "rsi_ema_strategy_settings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RsiEmaStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ============================================================
    // 🔹 УНИВЕРСАЛЬНЫЕ ПОЛЯ (как SmartFusion)
    // ============================================================

    @Column(nullable = false)
    private Long chatId;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String symbol = "BTCUSDT";

    @Builder.Default
    @Column(nullable = false)
    private String timeframe = "1m";

    @Builder.Default
    @Column(name = "candle_limit", nullable = false)
    private int cachedCandlesLimit = 150;

    @Builder.Default
    private double capitalUsd = 50.0;

    @Builder.Default
    private double commissionPct = 0.04;

    @Builder.Default
    private double riskPerTradePct = 1.0;

    @Builder.Default
    private double dailyLossLimitPct = 5.0;

    @Builder.Default
    private boolean reinvestProfit = false;

    @Builder.Default
    private int leverage = 1;

    @Builder.Default
    private double takeProfitPct = 0.5;

    @Builder.Default
    private double stopLossPct = 0.5;

    // ============================================================
    // 🔸 ПАРАМЕТРЫ RSI + EMA
    // ============================================================

    @Builder.Default
    private int rsiPeriod = 14;

    @Builder.Default
    private int emaFast = 9;

    @Builder.Default
    private int emaSlow = 21;

    @Builder.Default
    private double rsiBuyThreshold = 30.0;

    @Builder.Default
    private double rsiSellThreshold = 70.0;

    // ============================================================
    // SYSTEM FIELDS
    // ============================================================

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private NetworkType networkType = NetworkType.MAINNET;

    @Builder.Default
    private boolean active = false;

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();


    @PrePersist
    public void onCreate() {
        if (cachedCandlesLimit <= 0) cachedCandlesLimit = 150;
        if (capitalUsd <= 0) capitalUsd = 50.0;
        if (commissionPct <= 0) commissionPct = 0.04;
        if (riskPerTradePct <= 0) riskPerTradePct = 1.0;
        if (dailyLossLimitPct <= 0) dailyLossLimitPct = 5.0;
        if (takeProfitPct <= 0) takeProfitPct = 0.5;
        if (stopLossPct <= 0) stopLossPct = 0.5;
    }
}
