package com.chicu.aitradebot.domain;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Locale;

@Entity
@Table(
        name = "strategy_settings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_strategy_settings_chat_type",
                columnNames = {"chat_id", "type"}
        ),
        indexes = {
                @Index(name = "ix_strategy_settings_chat", columnList = "chat_id"),
                @Index(name = "ix_strategy_settings_chat_type", columnList = "chat_id,type")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategySettings {

    private static final BigDecimal PROB_MIN = BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
    private static final BigDecimal PROB_MAX = BigDecimal.ONE.setScale(6, RoundingMode.HALF_UP);

    /**
     * Технический дефолт для ML-gate, если включили gate, но threshold ещё не задан.
     * Бизнес-дефолт всё равно должен выставлять сервис, но entity не даст сохранить "пустой gate".
     */
    private static final BigDecimal DEFAULT_GATE_MIN_PROB = new BigDecimal("0.550000");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StrategyType type;

    // контекст
    @Column(name = "exchange_name", nullable = false, length = 32)
    private String exchangeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "network_type", nullable = false, length = 16)
    private NetworkType networkType;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false, length = 16)
    private String timeframe;

    @Builder.Default
    @Column(name = "cached_candles_limit", nullable = false)
    private Integer cachedCandlesLimit = 500;

    @Column(name = "account_asset", length = 16)
    private String accountAsset;

    public enum CapitalMode { ALL, FIX, PCT }

    @Enumerated(EnumType.STRING)
    @Column(name = "capital_mode", nullable = false, length = 8)
    @Builder.Default
    private CapitalMode capitalMode = CapitalMode.ALL;

    @Column(name = "capital_value", precision = 18, scale = 6)
    private BigDecimal capitalValue;

    /**
     * Единый режим управления:
     * MANUAL  — без ML и без автотюна
     * HYBRID  — ML-gate обязателен, автотюн запрещён
     * AI      — автотюн обязателен, ML-gate по желанию (но если включён — threshold обязателен)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "advanced_control_mode", nullable = false, length = 16)
    @Builder.Default
    private AdvancedControlMode advancedControlMode = AdvancedControlMode.MANUAL;

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

    // ===========================
    // Convenience (для стратегии)
    // ===========================

    public boolean isManualMode() {
        return getControlModeSafe() == AdvancedControlMode.MANUAL;
    }

    public boolean isHybridMode() {
        return getControlModeSafe() == AdvancedControlMode.HYBRID;
    }

    public boolean isAiMode() {
        return getControlModeSafe() == AdvancedControlMode.AI;
    }

    /**
     * Реально ли включён ML-gate в принятии решений (не просто флаг в БД).
     */
    public boolean isMlGateEffective() {
        AdvancedControlMode m = getControlModeSafe();
        return m != AdvancedControlMode.MANUAL && mlGateEnabled;
    }

    /**
     * Гарантированно валидный threshold для ML-gate, если gate активен.
     * Если gate не активен — вернёт null.
     */
    public BigDecimal getEffectiveGateMinProbOrNull() {
        if (!isMlGateEffective()) return null;
        BigDecimal v = (gateMinProb != null) ? gateMinProb : DEFAULT_GATE_MIN_PROB;
        return clampProb(v);
    }

    public BigDecimal getEffectiveCapitalValueOrNull() {
        CapitalMode m = (capitalMode != null ? capitalMode : CapitalMode.ALL);
        return (m == CapitalMode.ALL) ? null : capitalValue;
    }

    // ===========================
    // JPA hooks
    // ===========================

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = (this.createdAt != null) ? this.createdAt : now;
        this.updatedAt = (this.updatedAt != null) ? this.updatedAt : now;

        // только нормализация/валидация (дефолты задаёт сервис)
        normalizeAll();
        validateRequiredForDb();

        normalizeCapital();
        enforceControlModeHardRules();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();

        normalizeAll();
        validateRequiredForDb();

        normalizeCapital();
        enforceControlModeHardRules();
    }

    /**
     * Жёсткие правила, чтобы режим управления не превращался в "всё включено всегда".
     */
    private void enforceControlModeHardRules() {
        AdvancedControlMode m = getControlModeSafe();

        // MANUAL = жёстко выключаем “умные” флаги и пороги
        if (m == AdvancedControlMode.MANUAL) {
            this.autoTuneEnabled = false;
            this.mlGateEnabled = false;
            this.gateMinProb = null;
            return;
        }

        // HYBRID = ML-gate обязателен, автотюн запрещён
        if (m == AdvancedControlMode.HYBRID) {
            this.autoTuneEnabled = false;
            this.mlGateEnabled = true;
            this.gateMinProb = clampProb(this.gateMinProb != null ? this.gateMinProb : DEFAULT_GATE_MIN_PROB);
            return;
        }

        // AI = автотюн обязателен
        if (m == AdvancedControlMode.AI) {
            this.autoTuneEnabled = true;

            // threshold нужен только если gate включён
            if (this.mlGateEnabled) {
                this.gateMinProb = clampProb(this.gateMinProb != null ? this.gateMinProb : DEFAULT_GATE_MIN_PROB);
            } else {
                this.gateMinProb = null;
            }
        }
    }

    private void normalizeAll() {
        this.exchangeName = normalizeUpperRequired(this.exchangeName);
        this.symbol       = normalizeUpperRequired(this.symbol);
        this.timeframe    = normalizeLowerRequired(this.timeframe);

        this.accountAsset = normalizeUpperNullable(this.accountAsset);
        this.runPhase     = normalizeUpperNullable(this.runPhase);

        this.mlModelKey     = normalizeTrimNullable(this.mlModelKey);
        this.mlSchemaHash   = normalizeTrimNullable(this.mlSchemaHash);
        this.mlModelVersion = normalizeTrimNullable(this.mlModelVersion);

        if (this.cachedCandlesLimit != null && this.cachedCandlesLimit < 50) {
            this.cachedCandlesLimit = 50;
        }
        if (this.cachedCandlesLimit == null) {
            this.cachedCandlesLimit = 500; // технический дефолт, НЕ бизнес-дефолт
        }

        if (this.advancedControlMode == null) this.advancedControlMode = AdvancedControlMode.MANUAL;
        if (this.capitalMode == null) this.capitalMode = CapitalMode.ALL;

        if (this.mlConfidence == null) this.mlConfidence = BigDecimal.ZERO;
        if (this.totalProfitPct == null) this.totalProfitPct = BigDecimal.ZERO;

        // нормализуем scale для чисел, чтобы не плодить разные представления в БД/логах
        this.mlConfidence = safeScale6(this.mlConfidence);
        this.totalProfitPct = safeScale6(this.totalProfitPct);

        if (this.gateMinProb != null) {
            this.gateMinProb = clampProb(this.gateMinProb);
        }
    }

    private void validateRequiredForDb() {
        // поля nullable=false в БД: без сервиса пусть падает сразу и понятно
        if (chatId == null) throw new IllegalStateException("StrategySettings.chatId is null");
        if (type == null) throw new IllegalStateException("StrategySettings.type is null");
        if (exchangeName == null) throw new IllegalStateException("StrategySettings.exchangeName is null (set defaults in StrategySettingsService)");
        if (networkType == null) throw new IllegalStateException("StrategySettings.networkType is null (set defaults in StrategySettingsService)");
        if (symbol == null) throw new IllegalStateException("StrategySettings.symbol is null (set defaults in StrategySettingsService)");
        if (timeframe == null) throw new IllegalStateException("StrategySettings.timeframe is null (set defaults in StrategySettingsService)");
        if (cachedCandlesLimit == null) throw new IllegalStateException("StrategySettings.cachedCandlesLimit is null");
    }

    private void normalizeCapital() {
        CapitalMode m = (this.capitalMode != null ? this.capitalMode : CapitalMode.ALL);

        if (m == CapitalMode.ALL) {
            this.capitalValue = null;
            return;
        }

        if (this.capitalValue == null) return;

        if (this.capitalValue.signum() <= 0) {
            this.capitalValue = null;
            return;
        }

        if (m == CapitalMode.PCT && this.capitalValue.compareTo(BigDecimal.valueOf(100)) > 0) {
            this.capitalValue = BigDecimal.valueOf(100);
        }
    }

    private AdvancedControlMode getControlModeSafe() {
        return (advancedControlMode != null) ? advancedControlMode : AdvancedControlMode.MANUAL;
    }

    private static BigDecimal clampProb(BigDecimal v) {
        if (v == null) return null;
        BigDecimal x = v.setScale(6, RoundingMode.HALF_UP);
        if (x.compareTo(PROB_MIN) < 0) return PROB_MIN;
        if (x.compareTo(PROB_MAX) > 0) return PROB_MAX;
        return x;
    }

    private static BigDecimal safeScale6(BigDecimal v) {
        if (v == null) return null;
        return v.setScale(6, RoundingMode.HALF_UP);
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

    private static String normalizeUpperRequired(String s) {
        String v = normalizeTrimNullable(s);
        if (v == null) return null;
        return v.toUpperCase(Locale.ROOT);
    }

    private static String normalizeLowerRequired(String s) {
        String v = normalizeTrimNullable(s);
        if (v == null) return null;
        return v.toLowerCase(Locale.ROOT);
    }
}