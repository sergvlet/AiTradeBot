package com.chicu.aitradebot.ai.ml.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MlTrainResponse {

    /** FastAPI: ok */
    private boolean ok;

    /** FastAPI: error */
    private String error;

    /** FastAPI: tsMs */
    @JsonAlias({"ts", "timestamp"})
    private Long tsMs;

    // =========================
    // ✅ поля FastAPI /train (текущие)
    // =========================

    /** FastAPI: modelKey */
    @JsonAlias({"model_key", "key"})
    private String modelKey;

    /** FastAPI: modelVersion */
    @JsonAlias({"model_version", "version"})
    private String modelVersion;

    /** FastAPI: schemaHash */
    @JsonAlias({"schema_hash"})
    private String schemaHash;

    /** FastAPI: metricsJson */
    @JsonAlias({"metrics_json"})
    private String metricsJson;

    // =========================
    // ✅ опциональные/старые поля (если появятся в ответе)
    // =========================

    /** old/optional: model_saved */
    @JsonProperty("model_saved")
    private Boolean modelSaved;

    /** old/optional: n_samples */
    @JsonProperty("n_samples")
    private Integer nSamples;

    /** old/optional: n_features */
    @JsonProperty("n_features")
    private Integer nFeatures;

    /** old/optional: backend */
    private String backend;

    /**
     * optional: featureSchema
     * (и алиас "schema" / "schemaFields" для старых ответов)
     */
    @JsonProperty("featureSchema")
    private List<String> featureSchema;

    @JsonProperty("schema")
    private void setSchemaAlias(List<String> schema) {
        if (this.featureSchema == null || this.featureSchema.isEmpty()) {
            this.featureSchema = schema;
        }
    }

    @JsonProperty("schemaFields")
    private void setSchemaFieldsAlias(List<String> schema) {
        if (this.featureSchema == null || this.featureSchema.isEmpty()) {
            this.featureSchema = schema;
        }
    }

    // =========================
    // helpers
    // =========================

    public static MlTrainResponse ok(String modelKey, String modelVersion, String schemaHash, String metricsJson) {
        MlTrainResponse r = new MlTrainResponse();
        r.ok = true;
        r.modelKey = blankToNull(modelKey);
        r.modelVersion = blankToNull(modelVersion);
        r.schemaHash = blankToNull(schemaHash);
        r.metricsJson = blankToNull(metricsJson);
        r.tsMs = System.currentTimeMillis();
        r.error = null;
        return r;
    }

    public static MlTrainResponse fail(String error) {
        MlTrainResponse r = new MlTrainResponse();
        r.ok = false;
        r.error = (error == null || error.isBlank()) ? "train_failed" : error.trim();
        r.tsMs = System.currentTimeMillis();
        r.modelSaved = Boolean.FALSE;
        return r;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}