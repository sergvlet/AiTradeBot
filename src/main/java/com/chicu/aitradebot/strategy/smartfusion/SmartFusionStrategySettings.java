package com.chicu.aitradebot.strategy.smartfusion;

import com.chicu.aitradebot.common.enums.NetworkType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * 🧠 Smart Fusion AI v3.0 — полные настройки стратегии.
 * Все параметры берутся из БД и могут изменяться пользователем.
 */
@Entity
@Table(name = "smart_fusion_strategy_settings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmartFusionStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Владелец настроек (пользователь/чат) */
    @Column(nullable = false)
    private Long chatId;

    /** Торговый символ (например, BTCUSDT) */
    @Column(nullable = false, length = 20)
    private String symbol;

    /** Биржа: BINANCE / BYBIT / OKX / ... */
    @Builder.Default
    @Column(nullable = false, length = 32)
    private String exchange = "BINANCE";

    /** Сеть: MAINNET / TESTNET (используется клиентом биржи) */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "network_type", nullable = false, length = 16)
    private NetworkType networkType = NetworkType.TESTNET;

    // === Базовые параметры ===
    @Builder.Default
    @Column(nullable = false, length = 8)
    private String timeframe = "15m";

    @Builder.Default
    @Column(name = "candle_limit", nullable = false)
    private int candleLimit = 200;

    /** Комиссия в % на цикл (вход+выход), например 0.1 = 0.1% */
    @Builder.Default
    @Column(name = "commission_pct", nullable = false)
    private double commissionPct = 0.1;

    @Builder.Default
    @Column(nullable = false)
    private double leverage = 3.0;

    /** ⏱️ Интервал цикла стратегии (сек) */
    @Builder.Default
    @Column(name = "tick_interval_sec", nullable = false)
    private int tickIntervalSec = 10;

    // === Капитал и риск ===
    @Builder.Default
    @Column(name = "capital_usd", nullable = false)
    private double capitalUsd = 1000.0;

    @Builder.Default
    @Column(name = "risk_per_trade_pct", nullable = false)
    private double riskPerTradePct = 2.0;

    /** Лимит дневных потерь, % */
    @Builder.Default
    @Column(name = "daily_loss_limit_pct", nullable = false)
    private double dailyLossLimitPct = 3.0;

    /** Порог волатильности для блокировки входа, % */
    @Builder.Default
    @Column(name = "volatility_threshold_pct", nullable = false)
    private double volatilityThresholdPct = 2.0;

    /** Окно анализа волатильности (сек) — по умолчанию 5 минут */
    @Builder.Default
    @Column(name = "volatility_window_sec", nullable = false)
    private int volatilityWindowSec = 300;

    /** 🛡 Дополнительная защита от волатильности */
    @Builder.Default
    @Column(name = "volatility_shield_pct", nullable = false)
    private BigDecimal volatilityShieldPct = BigDecimal.valueOf(0.05);

    // === Smart Sizing ===
    @Builder.Default
    @Column(name = "smart_sizing_start_pct", nullable = false)
    private double smartSizingStartPct = 1.0;

    @Builder.Default
    @Column(name = "smart_sizing_min_pct", nullable = false)
    private double smartSizingMinPct = 0.5;

    @Builder.Default
    @Column(name = "smart_sizing_max_pct", nullable = false)
    private double smartSizingMaxPct = 5.0;

    // === Reinforcement Learning (адаптивные множители TP/SL) ===
    @Builder.Default
    @Column(name = "rl_alpha", nullable = false)
    private double rlAlpha = 0.15;

    @Builder.Default
    @Column(name = "rl_drawdown_penalty_floor", nullable = false)
    private double rlDrawdownPenaltyFloor = -3.0;

    @Builder.Default
    @Column(name = "rl_step_tp", nullable = false)
    private double rlStepTp = 0.05;

    @Builder.Default
    @Column(name = "rl_step_sl", nullable = false)
    private double rlStepSl = 0.05;

    @Builder.Default
    @Column(name = "rl_step_size", nullable = false)
    private double rlStepSize = 0.1;

    @Builder.Default
    @Column(name = "rl_min_tp_mult", nullable = false)
    private double rlMinTpMult = 0.5;

    @Builder.Default
    @Column(name = "rl_max_tp_mult", nullable = false)
    private double rlMaxTpMult = 2.0;

    @Builder.Default
    @Column(name = "rl_min_sl_mult", nullable = false)
    private double rlMinSlMult = 0.5;

    @Builder.Default
    @Column(name = "rl_max_sl_mult", nullable = false)
    private double rlMaxSlMult = 2.0;

    // === TP / SL и фильтры ===
    @Builder.Default
    @Column(name = "take_profit_atr_mult", nullable = false)
    private double takeProfitAtrMult = 2.0;

    @Builder.Default
    @Column(name = "stop_loss_atr_mult", nullable = false)
    private double stopLossAtrMult = 1.0;

    @Builder.Default
    @Column(name = "atr_period", nullable = false)
    private int atrPeriod = 14;

    // === Индикаторы ===
    @Builder.Default
    @Column(name = "ema_fast_period", nullable = false)
    private int emaFastPeriod = 9;

    @Builder.Default
    @Column(name = "ema_slow_period", nullable = false)
    private int emaSlowPeriod = 21;

    @Builder.Default
    @Column(name = "rsi_period", nullable = false)
    private int rsiPeriod = 14;

    @Builder.Default
    @Column(name = "rsi_buy_threshold", nullable = false)
    private double rsiBuyThreshold = 45.0;

    @Builder.Default
    @Column(name = "rsi_sell_threshold", nullable = false)
    private double rsiSellThreshold = 55.0;

    @Builder.Default
    @Column(name = "bollinger_period", nullable = false)
    private int bollingerPeriod = 20;

    @Builder.Default
    @Column(name = "bollingerk", nullable = false)
    private double bollingerK = 2.0;

    // === ML Confirm thresholds ===
    @Builder.Default
    @Column(name = "ml_buy_min", nullable = false)
    private double mlBuyMin = 0.65;

    @Builder.Default
    @Column(name = "ml_sell_min", nullable = false)
    private double mlSellMin = 0.55;

    // === Прочее ===
    @Builder.Default
    @Column(name = "auto_retrain", nullable = false)
    private boolean autoRetrain = false;

    @Builder.Default
    @Column(name = "reinvest_profit", nullable = false)
    private boolean reinvestProfit = true;
}
