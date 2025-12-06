package com.chicu.aitradebot.strategy.fibonacci;

import com.chicu.aitradebot.common.enums.NetworkType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "fibonacci_grid_strategy_settings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FibonacciGridStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long chatId;

    /** Основной символ */
    private String symbol = "BTCUSDT";

    /** Сколько уровней строить */
    private int gridLevels = 6;

    /** Процент расстояния между уровнями */
    private double distancePct = 0.5;

    /** Объём BUY/SELL ордера */
    private double baseOrderVolume = 50.0;

    /** Take Profit (%) */
    private double takeProfitPct = 0.7;

    /** Stop Loss (%) */
    private double stopLossPct = 0.7;

    /** Таймфрейм */
    private String timeframe = "1m";

    /** Сколько свечей кешировать */
    private int candleLimit = 300;

    /** Сеть */
    @Enumerated(EnumType.STRING)
    private NetworkType networkType = NetworkType.MAINNET;

    /** Активна ли стратегия */
    private boolean active = false;

    private Instant createdAt = Instant.now();
}
