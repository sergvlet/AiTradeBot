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
    @Column(nullable = false)
    private StrategyType type;

    // =====================================================================
    // ИНСТРУМЕНТ
    // =====================================================================

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String timeframe;

    @Builder.Default
    private Integer cachedCandlesLimit = 500;

    // =====================================================================
    // КАПИТАЛ / РИСК (ОБЩИЕ)
    // =====================================================================

    /**
     * ⚠️ TODO: DEPRECATE
     * Историческое поле — в будущем капитал берётся ТОЛЬКО с биржи
     */
    @Column(precision = 18, scale = 6)
    private BigDecimal capitalUsd;

    // =====================================================================
// 💰 АКТИВ АККАУНТА (ВЫБРАННЫЙ, FREE > 0)
// =====================================================================

    /**
     * Актив аккаунта, которым оперирует стратегия (USDT, BTC, ETH и т.д.)
     * Выбирается автоматически из баланса (free > 0) или пользователем через UI.
     */
    @Column(name = "account_asset")
    private String accountAsset;


    /**
     * ⚠️ TODO: DEPRECATE
     * Комиссии будут браться из ExchangeClient#getAccountInfo
     */
    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal commissionPct = BigDecimal.valueOf(0.05);

    @Column(precision = 10, scale = 4)
    private BigDecimal riskPerTradePct;

    @Column(precision = 10, scale = 4)
    private BigDecimal dailyLossLimitPct;

    @Column(nullable = false)
    private boolean reinvestProfit;


    /**
     * ⚠️ TODO: DEPRECATE
     * Плечо должно приходить с аккаунта биржи
     */
    @Builder.Default
    private int leverage = 1;

    // =====================================================================
    // 🔥 ЛИМИТЫ ИСПОЛЬЗОВАНИЯ СРЕДСТВ (НОВОЕ, КЛЮЧЕВОЕ)
    // =====================================================================

    /** Максимальная сумма, доступная стратегии (USDT) */
    @Column(precision = 18, scale = 6)
    private BigDecimal maxExposureUsd;

    /** Максимальный процент от баланса */
    @Column(precision = 5, scale = 2)
    private Integer maxExposurePct;

    // =====================================================================
    // TP / SL (ГЛОБАЛЬНЫЕ)
    // =====================================================================

    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal takeProfitPct = BigDecimal.valueOf(1.0);

    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal stopLossPct = BigDecimal.valueOf(1.0);

    // =====================================================================
    // AI / ML / УПРАВЛЕНИЕ
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
    // СОСТОЯНИЕ
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
    // СЛУЖЕБНЫЕ
    // =====================================================================

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // =====================================================================
    // СОВМЕСТИМОСТЬ
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
