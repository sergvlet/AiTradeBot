package com.chicu.aitradebot.strategy.rsie;

import jakarta.persistence.*;
import lombok.*;

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

    private Long chatId;

    @Column(nullable = false, length = 20)
    private String symbol;

    // === INDICATORS ===
    @Builder.Default
    private int rsiPeriod = 14;

    @Builder.Default
    private int emaFast = 9;

    @Builder.Default
    private int emaSlow = 21;

    // === THRESHOLDS ===
    @Builder.Default
    private double rsiBuyThreshold = 30;

    @Builder.Default
    private double rsiSellThreshold = 70;

    @Builder.Default
    private String timeframe = "1m";

    @Builder.Default
    private int candleLimit = 150;
}
