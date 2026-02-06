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

    // =====================================================
    // ИДЕНТИФИКАЦИЯ / КОНТЕКСТ
    // =====================================================

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    // =====================================================
    // ИНСТРУМЕНТ / ДАННЫЕ
    // =====================================================

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false, length = 16)
    private String timeframe;

    @Builder.Default
    @Column(name = "cached_candles_limit", nullable = false)
    private Integer cachedCandlesLimit = 500;

    // =====================================================
    // BALANCE / CAPITAL (ЕДИНЫЙ ИСТОЧНИК РИСКА)
    // =====================================================

    /**
     * Актив, по которому берём баланс (например USDT).
     * Это именно выбор пользователя.
     */
    @Column(name = "account_asset", length = 16)
    private String accountAsset;

    /**
     * ✅ Режим капитала стратегии:
     * ALL  - использовать весь доступный free баланс выбранного актива
     * FIX  - использовать фиксированную сумму (но не больше free баланса)
     * PCT  - использовать % от free баланса
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "capital_mode", nullable = false, length = 8)
    @Builder.Default
    private CapitalMode capitalMode = CapitalMode.ALL;

    /**
     * ✅ Значение капитала:
     * - для FIX: сумма (например 40)
     * - для PCT: процент (например 20 = 20%)
     * - для ALL: всегда null
     */
    @Column(name = "capital_value", precision = 18, scale = 6)
    private BigDecimal capitalValue;

    public enum CapitalMode {
        ALL, FIX, PCT
    }

    // =====================================================
    // ADVANCED / AI
    // =====================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "advanced_control_mode", nullable = false, length = 16)
    @Builder.Default
    private AdvancedControlMode advancedControlMode = AdvancedControlMode.MANUAL;

    /**
     * Фаза исполнения (COLLECT / BACKTEST / PAPER / LIVE и т.д.)
     */
    @Column(name = "run_phase", length = 24)
    private String runPhase;

    @Builder.Default
    @Column(name = "auto_tune_enabled", nullable = false)
    private boolean autoTuneEnabled = false;

    @Builder.Default
    @Column(name = "ml_gate_enabled", nullable = false)
    private boolean mlGateEnabled = false;

    @Column(name = "ml_model_key", length = 160)
    private String mlModelKey;

    @Column(name = "ml_schema_hash", length = 80)
    private String mlSchemaHash;

    @Column(name = "ml_model_version", length = 80)
    private String mlModelVersion;

    @Column(name = "gate_min_prob", precision = 10, scale = 6)
    private BigDecimal gateMinProb;

    @Builder.Default
    @Column(name = "ml_confidence", precision = 10, scale = 6, nullable = false)
    private BigDecimal mlConfidence = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "total_profit_pct", precision = 12, scale = 6, nullable = false)
    private BigDecimal totalProfitPct = BigDecimal.ZERO;

    // =====================================================
    // СОСТОЯНИЕ / ВРЕМЯ
    // =====================================================

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

    // =====================================================
    // DOMAIN HELPERS (чисто для удобства)
    // =====================================================

    /**
     * Возвращает валидное значение capitalValue в зависимости от режима,
     * либо null если лимит не задан/невалиден.
     */
    public BigDecimal getEffectiveCapitalValueOrNull() {
        CapitalMode m = (capitalMode != null ? capitalMode : CapitalMode.ALL);
        if (m == CapitalMode.ALL) return null;
        return capitalValue;
    }

    // =====================================================
    // LIFECYCLE
    // =====================================================

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = (this.createdAt != null) ? this.createdAt : now;
        this.updatedAt = (this.updatedAt != null) ? this.updatedAt : now;

        // not-null поля при создании
        if (this.symbol == null || this.symbol.trim().isEmpty()) this.symbol = "BTCUSDT";
        this.symbol = this.symbol.trim().toUpperCase(Locale.ROOT);

        if (this.timeframe == null || this.timeframe.trim().isEmpty()) this.timeframe = "1m";
        this.timeframe = this.timeframe.trim().toLowerCase(Locale.ROOT);

        if (this.cachedCandlesLimit == null || this.cachedCandlesLimit < 50) this.cachedCandlesLimit = 500;

        this.exchangeName = normalizeUpperNullable(this.exchangeName);
        this.accountAsset = normalizeUpperNullable(this.accountAsset);

        if (this.advancedControlMode == null) this.advancedControlMode = AdvancedControlMode.MANUAL;
        if (this.capitalMode == null) this.capitalMode = CapitalMode.ALL;

        // нормализация строк AI/ML
        this.runPhase = normalizeUpperNullable(this.runPhase);
        this.mlModelKey = normalizeTrimNullable(this.mlModelKey);
        this.mlSchemaHash = normalizeTrimNullable(this.mlSchemaHash);
        this.mlModelVersion = normalizeTrimNullable(this.mlModelVersion);

        if (this.mlConfidence == null) this.mlConfidence = BigDecimal.ZERO;
        if (this.totalProfitPct == null) this.totalProfitPct = BigDecimal.ZERO;

        normalizeCapital();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();

        if (this.symbol != null && !this.symbol.trim().isEmpty()) {
            this.symbol = this.symbol.trim().toUpperCase(Locale.ROOT);
        }
        if (this.timeframe != null && !this.timeframe.trim().isEmpty()) {
            this.timeframe = this.timeframe.trim().toLowerCase(Locale.ROOT);
        }

        this.exchangeName = normalizeUpperNullable(this.exchangeName);
        this.accountAsset = normalizeUpperNullable(this.accountAsset);

        if (this.cachedCandlesLimit != null && this.cachedCandlesLimit < 50) {
            this.cachedCandlesLimit = 50;
        }

        if (this.advancedControlMode == null) this.advancedControlMode = AdvancedControlMode.MANUAL;
        if (this.capitalMode == null) this.capitalMode = CapitalMode.ALL;

        // нормализация строк AI/ML
        this.runPhase = normalizeUpperNullable(this.runPhase);
        this.mlModelKey = normalizeTrimNullable(this.mlModelKey);
        this.mlSchemaHash = normalizeTrimNullable(this.mlSchemaHash);
        this.mlModelVersion = normalizeTrimNullable(this.mlModelVersion);

        if (this.mlConfidence == null) this.mlConfidence = BigDecimal.ZERO;
        if (this.totalProfitPct == null) this.totalProfitPct = BigDecimal.ZERO;

        normalizeCapital();
    }

    /**
     * ✅ Правила:
     * - ALL => capitalValue=null
     * - FIX => capitalValue>0 иначе null
     * - PCT => 0<capitalValue<=100 иначе null
     */
    private void normalizeCapital() {
        CapitalMode m = (this.capitalMode != null ? this.capitalMode : CapitalMode.ALL);

        if (m == CapitalMode.ALL) {
            this.capitalValue = null;
            return;
        }

        if (this.capitalValue == null) return;

        // <=0 => null
        if (this.capitalValue.signum() <= 0) {
            this.capitalValue = null;
            return;
        }

        if (m == CapitalMode.PCT) {
            // >100 => 100 (можно и null, но UX лучше clamp)
            if (this.capitalValue.compareTo(BigDecimal.valueOf(100)) > 0) {
                this.capitalValue = BigDecimal.valueOf(100);
            }
        }
    }

    private static String normalizeTrimNullable(String s) {
        if (s == null) return null;
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private static String normalizeUpperNullable(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty()) return null;
        return v.toUpperCase(Locale.ROOT);
    }
}
