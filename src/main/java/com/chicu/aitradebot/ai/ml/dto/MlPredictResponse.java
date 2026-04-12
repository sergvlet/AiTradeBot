package com.chicu.aitradebot.ai.ml.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class MlPredictResponse {

    /**
     * Новый основной флаг успешности.
     */
    private Boolean ok;

    /**
     * Каноническое поле вероятности.
     */
    @JsonAlias({"pWin", "proba", "probability", "score"})
    private Double pWin;

    private String label;
    private String reason;

    @JsonAlias({"error", "err"})
    private String error;

    private String version;
    private String modelVersion;
    private String modelKey;

    @JsonAlias({"schemaHash", "featureOrderHash", "feature_order_hash"})
    private String schemaHash;

    private Double recommendedThreshold;
    private Double floorThreshold;
    private Double ceilingThreshold;

    /**
     * Поля для backward compatibility со старым кодом.
     */
    @JsonAlias({"tsMs", "ts", "timestamp"})
    private Long tsMs;

    private Double threshold;
    private String decision;

    // =====================================================
    // Backward compatibility helpers
    // =====================================================

    /**
     * Старый код зовёт r.isOk()
     */
    public boolean isOk() {
        if (ok != null) {
            return ok;
        }
        return error == null || error.isBlank();
    }

    /**
     * Старый код зовёт r.getProba()
     */
    public Double getProba() {
        return pWin;
    }

    /**
     * На случай старого кода с setProba(...)
     */
    public void setProba(Double proba) {
        this.pWin = proba;
    }

    /**
     * Статическая фабрика ошибки для старого MlGateway и других классов.
     */
    public static MlPredictResponse fail(String error) {
        String msg = (error == null || error.isBlank()) ? "predict_failed" : error;
        return MlPredictResponse.builder()
                .ok(false)
                .pWin(null)
                .label(null)
                .reason(msg)
                .error(msg)
                .version(null)
                .modelVersion(null)
                .modelKey(null)
                .schemaHash(null)
                .recommendedThreshold(null)
                .floorThreshold(null)
                .ceilingThreshold(null)
                .tsMs(System.currentTimeMillis())
                .threshold(null)
                .decision(null)
                .build();
    }

    /**
     * Успешный shortcut.
     */
    public static MlPredictResponse ok(double proba) {
        return MlPredictResponse.builder()
                .ok(true)
                .pWin(proba)
                .tsMs(System.currentTimeMillis())
                .build();
    }

    // =====================================================
    // New-style helpers
    // =====================================================

    public boolean okOrDefault() {
        return isOk();
    }

    public double probabilityOrZero() {
        return pWin == null ? 0.0d : pWin;
    }

    public double recommendedThresholdOr(double fallback) {
        return recommendedThreshold == null ? fallback : recommendedThreshold;
    }

    public double floorThresholdOr(double fallback) {
        return floorThreshold == null ? fallback : floorThreshold;
    }

    public double ceilingThresholdOr(double fallback) {
        return ceilingThreshold == null ? fallback : ceilingThreshold;
    }

    public double thresholdOr(double fallback) {
        return threshold == null ? fallback : threshold;
    }

    public boolean hasProbability() {
        return pWin != null;
    }

    public boolean isFeatureOrderHashMismatch() {
        if (error == null) {
            return false;
        }
        return "featureOrder_hash_mismatch".equalsIgnoreCase(error)
                || "feature_order_hash_mismatch".equalsIgnoreCase(error);
    }

    public String errorOrReason() {
        if (error != null && !error.isBlank()) {
            return error;
        }
        return reason;
    }
}