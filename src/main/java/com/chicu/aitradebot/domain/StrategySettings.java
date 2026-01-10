package com.chicu.aitradebot.domain;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "strategy_settings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_strategy_settings_ctx",
                columnNames = {"chat_id", "type", "exchange_name", "network_type"}
        ),
        indexes = {
                @Index(name = "ix_strategy_settings_chat", columnList = "chat_id"),
                @Index(name = "ix_strategy_settings_ctx", columnList = "chat_id,type,exchange_name,network_type")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // =====================================================================
    // ИДЕНТИФИКАЦИЯ / КОНТЕКСТ
    // =====================================================================

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StrategyType type;

    @Column(name = "exchange_name", nullable = false, length = 32)
    private String exchangeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "network_type", nullable = false, length = 16)
    private NetworkType networkType;

    // =====================================================================
    // ИНСТРУМЕНТ / ДАННЫЕ
    // =====================================================================

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false, length = 16)
    private String timeframe;

    @Builder.Default
    @Column(name = "cached_candles_limit", nullable = false)
    private Integer cachedCandlesLimit = 500;

    // =====================================================================
    // GENERAL: АКТИВ / БЮДЖЕТ / ДНЕВНОЙ ЛИМИТ / РЕИНВЕСТ
    // =====================================================================

    @Column(name = "account_asset", length = 16)
    private String accountAsset;

    /** Бюджет стратегии в USD (если задан — лимитирует максимальную аллокацию) */
    @Column(name = "max_exposure_usd", precision = 18, scale = 6)
    private BigDecimal maxExposureUsd;

    /** Бюджет стратегии в % от доступного баланса (если задан) */
    @Column(name = "max_exposure_pct", precision = 10, scale = 4)
    private BigDecimal maxExposurePct;

    /** Максимальная потеря за день (%) */
    @Column(name = "daily_loss_limit_pct", precision = 10, scale = 4)
    private BigDecimal dailyLossLimitPct;

    @Builder.Default
    @Column(name = "reinvest_profit", nullable = false)
    private boolean reinvestProfit = false;

    // =====================================================================
    // RISK: ЛИМИТЫ / АНТИТИЛЬТ / ПРЕДОХРАНИТЕЛИ
    // =====================================================================

    /** Риск на сделку (%) */
    @Column(name = "risk_per_trade_pct", precision = 10, scale = 4)
    private BigDecimal riskPerTradePct;

    /** Мин. Risk/Reward */
    @Column(name = "min_risk_reward", precision = 10, scale = 4)
    private BigDecimal minRiskReward;

    /** Плечо (для спота обычно 1; держим универсально) */
    @Builder.Default
    @Column(nullable = false)
    private int leverage = 1;

    @Column(name = "allow_averaging")
    private Boolean allowAveraging;

    @Column(name = "cooldown_after_loss_seconds")
    private Integer cooldownAfterLossSeconds;

    @Column(name = "max_consecutive_losses")
    private Integer maxConsecutiveLosses;

    @Column(name = "max_drawdown_pct", precision = 10, scale = 4)
    private BigDecimal maxDrawdownPct;

    @Column(name = "max_drawdown_usd", precision = 18, scale = 6)
    private BigDecimal maxDrawdownUsd;

    @Column(name = "max_position_pct", precision = 10, scale = 4)
    private BigDecimal maxPositionPct;

    @Column(name = "max_position_usd", precision = 18, scale = 6)
    private BigDecimal maxPositionUsd;

    @Column(name = "max_trades_per_day")
    private Integer maxTradesPerDay;

    // =====================================================================
    // TRADE: ОГРАНИЧЕНИЯ СТРАТЕГИИ
    // =====================================================================

    @Column(name = "max_open_orders")
    private Integer maxOpenOrders;

    @Column(name = "cooldown_seconds")
    private Integer cooldownSeconds;

    // =====================================================================
    // ADVANCED: РЕЖИМ УПРАВЛЕНИЯ + AI метрики
    // =====================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "advanced_control_mode", nullable = false, length = 16)
    @Builder.Default
    private AdvancedControlMode advancedControlMode = AdvancedControlMode.MANUAL;

    @Builder.Default
    @Column(name = "ml_confidence", precision = 10, scale = 6, nullable = false)
    private BigDecimal mlConfidence = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "total_profit_pct", precision = 12, scale = 6, nullable = false)
    private BigDecimal totalProfitPct = BigDecimal.ZERO;

    // =====================================================================
    // СОСТОЯНИЕ / ВРЕМЯ
    // =====================================================================

    @Builder.Default
    @Column(nullable = false)
    private boolean active = false;

    private LocalDateTime startedAt;
    private LocalDateTime stoppedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Version
    private Integer version;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
