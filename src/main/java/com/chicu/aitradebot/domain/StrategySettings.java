package com.chicu.aitradebot.domain;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
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

    /** Привязка к пользователю / Telegram */
    @Column(nullable = false)
    private Long chatId;

    /** Тип стратегии */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StrategyType type;

    /** Торговая пара */
    @Column(nullable = false)
    private String symbol;

    /** Таймфрейм свечей */
    @Column(nullable = false)
    private String timeframe;

    /** Количество свечей в кэше */
    @Builder.Default
    private Integer cachedCandlesLimit = 500;

    // ========================= Капитал / риск =========================

    /** Капитал в USDT (может быть null для старых записей) */
    @Column(precision = 18, scale = 6)
    private BigDecimal capitalUsd;

    /** Комиссия биржи (%) */
    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal commissionPct = BigDecimal.valueOf(0.05); // 0.05%

    /** Риск на сделку, % (может быть null для старых записей) */
    @Column(precision = 10, scale = 4)
    private BigDecimal riskPerTradePct;

    /** Дневной лимит потерь, % (может быть null для старых записей) */
    @Column(precision = 10, scale = 4)
    private BigDecimal dailyLossLimitPct;

    /** Реинвест прибыли */
    @Builder.Default
    private boolean reinvestProfit = false;

    /** Плечо */
    @Builder.Default
    private int leverage = 1;

    // ========================= TP / SL =========================

    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal takeProfitPct = BigDecimal.valueOf(1.0); // 1.0%

    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal stopLossPct = BigDecimal.valueOf(1.0);   // 1.0%

    // ========================= PnL / ML =========================

    /** Накопленный PnL в % */
    @Builder.Default
    @Column(precision = 12, scale = 6)
    private BigDecimal totalProfitPct = BigDecimal.ZERO;

    /** Уверенность ML (0..1) */
    @Builder.Default
    @Column(precision = 10, scale = 6)
    private BigDecimal mlConfidence = BigDecimal.ZERO;

    // ========================= Версия / активность =========================

    @Builder.Default
    private int version = 1;

    @Builder.Default
    private boolean active = false;

    // ========================= Биржа + Сеть (на каждую стратегию) =========================

    /** BINANCE / BYBIT / OKX */
    @Column(length = 32)
    private String exchangeName;

    /** MAINNET / TESTNET */
    @Enumerated(EnumType.STRING)
    private NetworkType networkType;

    // ========================= Служебные поля =========================

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

    // ========================= Удобные геттеры для старого кода =========================

    /** Совместимость со старым кодом: заменить getStrategyType() -> возвращает поле type */
    @Transient
    public StrategyType getStrategyType() {
        return this.type;
    }

    /** Удобочитаемое имя из enum (для UI), чтобы не хранить его в БД */
    @Transient
    public String getStrategyName() {
        return (this.type != null) ? this.type.name().replace('_', ' ') : "Unknown";
    }
}
