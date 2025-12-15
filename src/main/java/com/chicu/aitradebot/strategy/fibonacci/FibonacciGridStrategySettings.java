package com.chicu.aitradebot.strategy.fibonacci;

import com.chicu.aitradebot.common.enums.NetworkType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "fibonacci_grid_strategy_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FibonacciGridStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Привязка к пользователю / чату */
    @Column(nullable = false)
    private Long chatId;

    /** Символ */
    @Builder.Default
    @Column(nullable = false)
    private String symbol = "BTCUSDT";

    /** Количество уровней */
    @Builder.Default
    @Column(nullable = false)
    private int gridLevels = 6;

    /** Расстояние между уровнями (%) */
    @Builder.Default
    @Column(nullable = false)
    private double distancePct = 0.5;

    /** Базовый объём BUY/SELL ордера */
    @Builder.Default
    @Column(nullable = false)
    private double baseOrderVolume = 50.0;

    /** Take Profit (%) */
    @Builder.Default
    @Column(nullable = false)
    private double takeProfitPct = 0.7;

    /** Stop Loss (%) */
    @Builder.Default
    @Column(nullable = false)
    private double stopLossPct = 0.7;

    /** Таймфрейм */
    @Builder.Default
    @Column(nullable = false)
    private String timeframe = "1m";

    /** Сколько свечей кешировать */
    @Builder.Default
    @Column(nullable = false)
    private int candleLimit = 300;

    /** Сеть */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NetworkType networkType = NetworkType.MAINNET;

    /** Активна ли стратегия */
    @Builder.Default
    @Column(nullable = false)
    private boolean active = false;

    // ============================================================
    // 🔹 ДОБАВЛЕНЫ ОБЩИЕ ПОЛЯ (как в SmartFusion)
    // ============================================================

    /** Капитал (USDT) */
    @Builder.Default
    @Column(nullable = false)
    private double capitalUsd = 100.0;

    /** Комиссия (%) */
    @Builder.Default
    @Column(nullable = false)
    private double commissionPct = 0.04;

    /** Риск на сделку (%) */
    @Builder.Default
    @Column(nullable = false)
    private double riskPerTradePct = 1.0;

    /** Дневной лимит потерь (%) */
    @Builder.Default
    @Column(nullable = false)
    private double dailyLossLimitPct = 5.0;

    /** Реинвестировать прибыль? */
    @Builder.Default
    @Column(nullable = false)
    private boolean reinvestProfit = false;

    /** Плечо */
    @Builder.Default
    @Column(nullable = false)
    private int leverage = 1;

    // ============================================================

    /** Время создания */
    @Builder.Default
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
