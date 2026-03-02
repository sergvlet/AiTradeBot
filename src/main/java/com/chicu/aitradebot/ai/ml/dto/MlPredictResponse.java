package com.chicu.aitradebot.ai.ml.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MlPredictResponse {

    private boolean ok;

    /** вероятность "BUY" (или “win”), 0..1 */
    @JsonAlias({"pWin", "p", "prob", "probability", "confidence", "score"})
    private Double proba;

    /** ключ модели (стабильный id), чтобы логировать/кешировать */
    @JsonAlias({"model_key", "model", "key", "modelId"})
    private String modelKey;

    /** версия модели/артефакта */
    @JsonAlias({"model_version", "version"})
    private String modelVersion;

    /** схема фичей */
    @JsonAlias({"schema_hash", "schema", "featuresSchema", "featureSchema"})
    private String schemaHash;

    /** sidecar backend (например: xgboost/joblib/no_model) */
    @JsonAlias({"backend"})
    private String backend;

    /** sidecar model_loaded */
    @JsonAlias({"model_loaded", "modelLoaded"})
    private Boolean modelLoaded;

    /** сообщение об ошибке (если ok=false) */
    @JsonAlias({"message", "reason"})
    private String error;

    /** опционально — таймстемп */
    @JsonAlias({"ts", "timestamp"})
    private Long tsMs;

    // =====================================================
    // ✅ Фабрики
    // =====================================================

    /** Backward-compat: старый код мог звать ok(proba, modelVersion) */
    public static MlPredictResponse ok(Double proba, String modelVersion) {
        return ok(proba, null, modelVersion, null);
    }

    public static MlPredictResponse ok(Double proba, String modelKey, String modelVersion, String schemaHash) {
        MlPredictResponse r = new MlPredictResponse();
        r.ok = true;
        r.tsMs = System.currentTimeMillis();
        r.proba = sanitizeProba(proba);
        r.modelKey = blankToNull(modelKey);
        r.modelVersion = blankToNull(modelVersion);
        r.schemaHash = blankToNull(schemaHash);
        r.backend = null;
        r.modelLoaded = null;
        r.error = null;
        return r;
    }

    public static MlPredictResponse fail(String error) {
        MlPredictResponse r = new MlPredictResponse();
        r.ok = false;
        r.tsMs = System.currentTimeMillis();
        r.error = blankToNull(error);
        r.proba = null;
        r.modelKey = null;
        r.modelVersion = null;
        r.schemaHash = null;
        r.backend = null;
        r.modelLoaded = null;
        return r;
    }

    // =====================================================
    // helpers
    // =====================================================

    private static Double sanitizeProba(Double p) {
        if (p == null) return null;
        if (!Double.isFinite(p)) return null;
        if (p < 0.0) return 0.0;
        if (p > 1.0) return 1.0;
        return p;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}