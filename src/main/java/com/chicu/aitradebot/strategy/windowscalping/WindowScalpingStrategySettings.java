package com.chicu.aitradebot.strategy.windowscalping;

import com.chicu.aitradebot.common.enums.NetworkType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;

@Entity
@Table(
        name = "window_scalping_strategy_settings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_window_scalping_settings_context",
                columnNames = {"chat_id", "exchange_name", "network_type", "symbol", "timeframe"}
        ),
        indexes = {
                @Index(name = "ix_window_scalping_ctx", columnList = "chat_id, exchange_name, network_type, symbol, timeframe"),
                @Index(name = "ix_window_scalping_chat", columnList = "chat_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WindowScalpingStrategySettings {

    public static final String DEFAULT_EXCHANGE = "BINANCE";
    public static final NetworkType DEFAULT_NETWORK = NetworkType.TESTNET;
    public static final String DEFAULT_SYMBOL = "BTCUSDT";
    public static final String DEFAULT_TIMEFRAME = "1m";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Builder.Default
    @Column(name = "exchange_name", nullable = false, length = 32)
    private String exchangeName = DEFAULT_EXCHANGE;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "network_type", nullable = false, length = 32)
    private NetworkType networkType = DEFAULT_NETWORK;

    @Builder.Default
    @Column(name = "symbol", nullable = false, length = 40)
    private String symbol = DEFAULT_SYMBOL;

    @Builder.Default
    @Column(name = "timeframe", nullable = false, length = 16)
    private String timeframe = DEFAULT_TIMEFRAME;

    // =====================================================
    // TP / SL (fallback static, в %)
    // =====================================================

    @Builder.Default
    @Column(name = "take_profit_pct", nullable = false, precision = 19, scale = 8)
    private BigDecimal takeProfitPct = new BigDecimal("0.60");

    @Builder.Default
    @Column(name = "stop_loss_pct", nullable = false, precision = 19, scale = 8)
    private BigDecimal stopLossPct = new BigDecimal("0.35");

    // =====================================================
    // AUTO TP / SL
    // =====================================================

    @Builder.Default
    @Column(name = "auto_tp_sl_enabled", nullable = false)
    private Boolean autoTpSlEnabled = Boolean.TRUE;

    @Builder.Default
    @Column(name = "auto_sl_from_range_factor", nullable = false, precision = 19, scale = 8)
    private BigDecimal autoSlFromRangeFactor = new BigDecimal("1.80");

    @Builder.Default
    @Column(name = "auto_tp_from_range_factor", nullable = false, precision = 19, scale = 8)
    private BigDecimal autoTpFromRangeFactor = new BigDecimal("5.50");

    @Builder.Default
    @Column(name = "auto_min_risk_reward", nullable = false, precision = 19, scale = 8)
    private BigDecimal autoMinRiskReward = new BigDecimal("2.40");

    @Builder.Default
    @Column(name = "auto_sl_min_pct", nullable = false, precision = 19, scale = 8)
    private BigDecimal autoSlMinPct = new BigDecimal("0.04");

    @Builder.Default
    @Column(name = "auto_sl_max_pct", nullable = false, precision = 19, scale = 8)
    private BigDecimal autoSlMaxPct = new BigDecimal("0.18");

    @Builder.Default
    @Column(name = "auto_tp_min_pct", nullable = false, precision = 19, scale = 8)
    private BigDecimal autoTpMinPct = new BigDecimal("0.10");

    @Builder.Default
    @Column(name = "auto_tp_max_pct", nullable = false, precision = 19, scale = 8)
    private BigDecimal autoTpMaxPct = new BigDecimal("0.80");

    @Builder.Default
    @Column(name = "auto_tp_ml_boost_factor", nullable = false, precision = 19, scale = 8)
    private BigDecimal autoTpMlBoostFactor = new BigDecimal("1.15");

    @Builder.Default
    @Column(name = "auto_tp_weak_signal_factor", nullable = false, precision = 19, scale = 8)
    private BigDecimal autoTpWeakSignalFactor = new BigDecimal("0.90");

    // =====================================================
    // WINDOW
    // =====================================================

    @Builder.Default
    @Column(name = "window_size", nullable = false)
    private Integer windowSize = 30;

    @Builder.Default
    @Column(name = "entry_from_low_pct", nullable = false)
    private Double entryFromLowPct = 20.0;

    @Builder.Default
    @Column(name = "entry_from_high_pct", nullable = false)
    private Double entryFromHighPct = 20.0;

    @Builder.Default
    @Column(name = "min_range_pct", nullable = false)
    private Double minRangePct = 0.25;

    @Builder.Default
    @Column(name = "max_spread_pct", nullable = false)
    private Double maxSpreadPct = 0.08;

    // =====================================================
    // TECH
    // =====================================================

    @Version
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        normalizeContext();
        normalizeWindowModel();
        normalizeRiskModel();
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        normalizeContext();
        normalizeWindowModel();
        normalizeRiskModel();
        updatedAt = Instant.now();
    }


    private void normalizeContext() {
        exchangeName = normalizeExchange(exchangeName);
        if (networkType == null) {
            networkType = DEFAULT_NETWORK;
        }
        symbol = normalizeSymbol(symbol);
        timeframe = normalizeTimeframe(timeframe);
    }
    private void normalizeWindowModel() {
        if (autoTpSlEnabled == null) {
            autoTpSlEnabled = Boolean.TRUE;
        }

        if (windowSize == null || windowSize < 5) {
            windowSize = 5;
        } else if (windowSize > 60) {
            windowSize = 60;
        }

        entryFromLowPct = clampDouble(entryFromLowPct, 5.0, 80.0, 20.0);
        entryFromHighPct = clampDouble(entryFromHighPct, 5.0, 80.0, 20.0);

        if (entryFromLowPct + entryFromHighPct > 95.0) {
            double scale = 95.0 / (entryFromLowPct + entryFromHighPct);
            entryFromLowPct = roundDouble(entryFromLowPct * scale);
            entryFromHighPct = roundDouble(entryFromHighPct * scale);
        }

        minRangePct = clampDouble(minRangePct, 0.001, 5.0, 0.25);
        maxSpreadPct = clampDouble(maxSpreadPct, 0.001, 5.0, 0.08);
    }


    public BigDecimal resolvedAutoSlMinPct() {
        return positiveOrDefault(autoSlMinPct, new BigDecimal("0.04"));
    }

    public BigDecimal resolvedAutoSlMaxPct() {
        BigDecimal min = resolvedAutoSlMinPct();
        BigDecimal max = positiveOrDefault(autoSlMaxPct, new BigDecimal("0.18"));
        return max.compareTo(min) < 0 ? min : max;
    }

    public BigDecimal resolvedAutoTpMinPct() {
        return positiveOrDefault(autoTpMinPct, new BigDecimal("0.10"));
    }

    public BigDecimal resolvedAutoTpMaxPct() {
        BigDecimal min = resolvedAutoTpMinPct();
        BigDecimal max = positiveOrDefault(autoTpMaxPct, new BigDecimal("0.80"));
        return max.compareTo(min) < 0 ? min : max;
    }

    public BigDecimal resolvedAutoMinRiskReward() {
        return positiveOrDefault(autoMinRiskReward, new BigDecimal("2.40"));
    }

    public void normalizeRiskModel() {
        BigDecimal slMin = resolvedAutoSlMinPct();
        BigDecimal slMax = resolvedAutoSlMaxPct();
        BigDecimal tpMin = resolvedAutoTpMinPct();
        BigDecimal tpMax = resolvedAutoTpMaxPct();
        BigDecimal minRr = resolvedAutoMinRiskReward();

        BigDecimal minTpByRr = slMin.multiply(minRr).setScale(8, RoundingMode.HALF_UP);
        if (tpMin.compareTo(minTpByRr) < 0) tpMin = minTpByRr;
        if (tpMax.compareTo(tpMin) < 0) tpMax = tpMin;

        autoSlMinPct = slMin.setScale(8, RoundingMode.HALF_UP);
        autoSlMaxPct = slMax.setScale(8, RoundingMode.HALF_UP);
        autoTpMinPct = tpMin.setScale(8, RoundingMode.HALF_UP);
        autoTpMaxPct = tpMax.setScale(8, RoundingMode.HALF_UP);
        autoMinRiskReward = minRr.setScale(8, RoundingMode.HALF_UP);

        BigDecimal effectiveSl = clamp(stopLossPct, slMin, slMax);
        BigDecimal effectiveTp = clamp(takeProfitPct, tpMin, tpMax);
        if (effectiveSl == null) effectiveSl = slMin;
        if (effectiveTp == null) effectiveTp = tpMin;

        BigDecimal minAllowedTp = effectiveSl.multiply(minRr).setScale(8, RoundingMode.HALF_UP);
        if (effectiveTp.compareTo(minAllowedTp) < 0) {
            effectiveTp = clamp(minAllowedTp, tpMin, tpMax);
        }

        stopLossPct = effectiveSl.setScale(8, RoundingMode.HALF_UP);
        takeProfitPct = effectiveTp.setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal positiveOrDefault(BigDecimal value, BigDecimal def) {
        return value != null && value.signum() > 0 ? value : def;
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value == null) return null;
        if (value.compareTo(min) < 0) return min;
        if (value.compareTo(max) > 0) return max;
        return value;
    }

    private static Double clampDouble(Double value, double min, double max, double def) {
        double v = value != null && Double.isFinite(value) ? value : def;
        if (v < min) v = min;
        if (v > max) v = max;
        return roundDouble(v);
    }

    private static Double roundDouble(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP).doubleValue();
    }

    public static String normalizeExchange(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_EXCHANGE;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public static String normalizeSymbol(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_SYMBOL;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public static String normalizeTimeframe(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_TIMEFRAME;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
