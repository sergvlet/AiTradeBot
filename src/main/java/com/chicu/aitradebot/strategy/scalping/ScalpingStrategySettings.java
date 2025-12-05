package com.chicu.aitradebot.strategy.scalping;

import com.chicu.aitradebot.common.enums.NetworkType;
import jakarta.persistence.*;
import lombok.*;

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
    private Long id;                 // ← обязательно нужно!

    @Column(nullable = false)
    private Long chatId;

    private String symbol;
    private String timeframe;

    @Column(name = "candle_limit")
    private int candleLimit;

    private double priceChangeThreshold;
    private double spreadThreshold;
    private double orderVolume;

    private int leverage;

    private int windowSize;

    private int cachedCandlesLimit;

    private double takeProfitPct;
    private double stopLossPct;

    @Enumerated(EnumType.STRING)
    private NetworkType networkType = NetworkType.TESTNET;

    private boolean active = false;

    private java.time.Instant createdAt;
}
