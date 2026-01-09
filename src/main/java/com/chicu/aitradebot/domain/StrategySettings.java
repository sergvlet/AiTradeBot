package com.chicu.aitradebot.domain;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "strategy_settings")
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
    // ИДЕНТИФИКАЦИЯ
    // =====================================================================

    @Column(nullable = false)
    private Long chatId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StrategyType type;

    // =====================================================================
    // ИНСТРУМЕНТ
    // =====================================================================

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false, length = 16)
    private String timeframe;

    @Builder.Default
    @Column(name = "cached_candles_limit")
    private Integer cachedCandlesLimit = 500;

    // =====================================================================
    // КАПИТАЛ / РИСК (LEGACY — потом можно удалить)
    // =====================================================================

    /** ⚠️ Историческое поле. В перспективе капитал должен браться с биржи. */
    @Column(precision = 18, scale = 6)
    private BigDecimal capitalUsd;

    /** Актив аккаунта (USDT, BTC, ETH и т.д.) */
    @Column(name = "account_asset", length = 16)
    private String accountAsset;

    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal commissionPct = BigDecimal.valueOf(0.05);

    @Column(precision = 10, scale = 4)
    private BigDecimal riskPerTradePct;

    @Column(precision = 10, scale = 4)
    private BigDecimal dailyLossLimitPct;

    @Builder.Default
    @Column(nullable = false)
    private boolean reinvestProfit = false;

    /** ⚠️ Историческое поле */
    @Builder.Default
    private int leverage = 1;

    // =====================================================================
    // ЛИМИТЫ ИСПОЛЬЗОВАНИЯ СРЕДСТВ (LEGACY)
    // =====================================================================

    @Column(precision = 18, scale = 6)
    private BigDecimal maxExposureUsd;

    @Column(precision = 5, scale = 2)
    private Integer maxExposurePct;

    // =====================================================================
    // TP / SL
    // =====================================================================

    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal takeProfitPct = BigDecimal.valueOf(1.0);

    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal stopLossPct = BigDecimal.valueOf(1.0);

    // =====================================================================
    // AI / УПРАВЛЕНИЕ
    // =====================================================================

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private AdvancedControlMode advancedControlMode = AdvancedControlMode.MANUAL;

    @Builder.Default
    @Column(precision = 10, scale = 6)
    private BigDecimal mlConfidence = BigDecimal.ZERO;

    // =====================================================================
    // PnL / СТАТИСТИКА
    // =====================================================================

    @Builder.Default
    @Column(precision = 12, scale = 6)
    private BigDecimal totalProfitPct = BigDecimal.ZERO;

    // =====================================================================
    // СОСТОЯНИЕ СТРАТЕГИИ
    // =====================================================================

    @Builder.Default
    private boolean active = false;

    @Builder.Default
    private int version = 1;

    // =====================================================================
    // БИРЖА / СЕТЬ
    // =====================================================================

    @Column(length = 32)
    private String exchangeName;

    @Enumerated(EnumType.STRING)
    private NetworkType networkType;

    // =====================================================================
    // ОГРАНИЧЕНИЯ СТРАТЕГИИ (LEGACY)
    // =====================================================================

    @Column(name = "max_open_orders")
    private Integer maxOpenOrders;

    @Column(name = "cooldown_seconds")
    private Integer cooldownSeconds;

    // =====================================================================
    // ✅ NEW (RISK V2) — добавлено, чтобы не падали Hibernate/Thymeleaf
    // Потом ненужное можно удалить одним блоком
    // =====================================================================

    /** Разрешить усреднение позиции */
    @Column(name = "allow_averaging")
    private Boolean allowAveraging;

    /** Пауза после убыточной сделки (в БД колонка так называется) */
    @Column(name = "cooldown_after_loss_seconds")
    private Integer cooldownAfterLossSeconds;

    /** Макс. подряд убыточных сделок */
    @Column(name = "max_consecutive_losses")
    private Integer maxConsecutiveLosses;

    /** Макс. просадка в процентах */
    @Column(name = "max_drawdown_pct", precision = 10, scale = 4)
    private BigDecimal maxDrawdownPct;

    /** Макс. просадка в USD */
    @Column(name = "max_drawdown_usd", precision = 18, scale = 6)
    private BigDecimal maxDrawdownUsd;

    /** Макс. размер позиции в % */
    @Column(name = "max_position_pct", precision = 5, scale = 2)
    private BigDecimal maxPositionPct;

    /** Макс. размер позиции в USD */
    @Column(name = "max_position_usd", precision = 18, scale = 6)
    private BigDecimal maxPositionUsd;

    /** Макс. сделок в день */
    @Column(name = "max_trades_per_day")
    private Integer maxTradesPerDay;

    /** Мин. риск/прибыль (RR) */
    @Column(name = "min_risk_reward", precision = 10, scale = 4)
    private BigDecimal minRiskReward;

    // =====================================================================
    // ВРЕМЯ ЖИЗНИ ЗАПИСИ
    // =====================================================================

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // =====================================================================
    // ВРЕМЯ ЗАПУСКА / ОСТАНОВКИ СТРАТЕГИИ
    // =====================================================================

    private LocalDateTime startedAt;
    private LocalDateTime stoppedAt;

    // =====================================================================
    // JPA HOOKS
    // =====================================================================

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

    // =====================================================================
    // ✅ ALIASES ДЛЯ СОВМЕСТИМОСТИ С THYMELEAF (чтобы не править шаблон сейчас)
    // =====================================================================

    @Transient
    public Integer getPauseAfterLossSeconds() {
        return this.cooldownAfterLossSeconds;
    }

    public void setPauseAfterLossSeconds(Integer v) {
        this.cooldownAfterLossSeconds = v;
    }

    // =====================================================================
    // СОВМЕСТИМОСТЬ / УТИЛИТЫ
    // =====================================================================

    @Transient
    public StrategyType getStrategyType() {
        return this.type;
    }

    @Transient
    public String getStrategyName() {
        return (this.type != null)
                ? this.type.name().replace('_', ' ')
                : "Unknown";
    }
}
