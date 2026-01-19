package com.chicu.aitradebot.domain;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

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
    // GENERAL
    // =====================================================================

    @Column(name = "account_asset", length = 16)
    private String accountAsset;

    @Column(name = "max_exposure_usd", precision = 18, scale = 6)
    private BigDecimal maxExposureUsd;

    @Column(name = "max_exposure_pct", precision = 10, scale = 4)
    private BigDecimal maxExposurePct;

    @Column(name = "daily_loss_limit_pct", precision = 10, scale = 4)
    private BigDecimal dailyLossLimitPct;

    @Builder.Default
    @Column(name = "reinvest_profit", nullable = false)
    private boolean reinvestProfit = false;

    // =====================================================================
    // RISK
    // =====================================================================

    @Column(name = "risk_per_trade_pct", precision = 10, scale = 4)
    private BigDecimal riskPerTradePct;

    @Column(name = "min_risk_reward", precision = 10, scale = 4)
    private BigDecimal minRiskReward;

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
    // TRADE
    // =====================================================================

    @Column(name = "max_open_orders")
    private Integer maxOpenOrders;

    @Column(name = "cooldown_seconds")
    private Integer cooldownSeconds;

    // =====================================================================
    // ADVANCED
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

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "stopped_at")
    private LocalDateTime stoppedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    private Integer version;

    // =====================================================================
    // LIFECYCLE
    // =====================================================================

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = (this.createdAt != null) ? this.createdAt : now;
        this.updatedAt = (this.updatedAt != null) ? this.updatedAt : now;

        // ✅ FIX: гарантируем NOT NULL поля только при создании записи (а не в сервисе на каждый save)
        if (this.symbol == null || this.symbol.trim().isEmpty()) {
            this.symbol = "BTCUSDT";
        } else {
            this.symbol = this.symbol.trim().toUpperCase(Locale.ROOT);
        }

        if (this.timeframe == null || this.timeframe.trim().isEmpty()) {
            this.timeframe = "1m";
        } else {
            this.timeframe = this.timeframe.trim().toLowerCase(Locale.ROOT);
        }

        if (this.cachedCandlesLimit == null || this.cachedCandlesLimit < 50) {
            this.cachedCandlesLimit = 500;
        }

        if (this.exchangeName != null) {
            this.exchangeName = this.exchangeName.trim().toUpperCase(Locale.ROOT);
        }

        if (this.accountAsset != null) {
            String a = this.accountAsset.trim().toUpperCase(Locale.ROOT);
            this.accountAsset = a.isEmpty() ? null : a;
        }

        if (this.advancedControlMode == null) {
            this.advancedControlMode = AdvancedControlMode.MANUAL;
        }

        if (this.mlConfidence == null) this.mlConfidence = BigDecimal.ZERO;
        if (this.totalProfitPct == null) this.totalProfitPct = BigDecimal.ZERO;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();

        // ✅ лёгкая нормализация без перезатирания значений дефолтами
        if (this.symbol != null) {
            String s = this.symbol.trim().toUpperCase(Locale.ROOT);
            this.symbol = s.isEmpty() ? this.symbol : s;
        }

        if (this.timeframe != null) {
            String tf = this.timeframe.trim().toLowerCase(Locale.ROOT);
            this.timeframe = tf.isEmpty() ? this.timeframe : tf;
        }

        if (this.exchangeName != null) {
            String ex = this.exchangeName.trim().toUpperCase(Locale.ROOT);
            this.exchangeName = ex.isEmpty() ? this.exchangeName : ex;
        }

        if (this.accountAsset != null) {
            String a = this.accountAsset.trim().toUpperCase(Locale.ROOT);
            this.accountAsset = a.isEmpty() ? null : a;
        }

        if (this.cachedCandlesLimit != null && this.cachedCandlesLimit < 50) {
            this.cachedCandlesLimit = 50;
        }

        if (this.advancedControlMode == null) {
            // на всякий случай (колонка NOT NULL)
            this.advancedControlMode = AdvancedControlMode.MANUAL;
        }

        if (this.mlConfidence == null) this.mlConfidence = BigDecimal.ZERO;
        if (this.totalProfitPct == null) this.totalProfitPct = BigDecimal.ZERO;
    }
}
