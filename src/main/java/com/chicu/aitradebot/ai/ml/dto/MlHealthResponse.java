package com.chicu.aitradebot.ai.ml.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ✅ Контракт /health (FastAPI)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MlHealthResponse {

    /** python: ok */
    private Boolean ok;

    /** python: ts (ms) */
    private Long ts;

    /** python: version */
    private String version;

    /** python: modelsDir */
    private String modelsDir;

    /** python: xgboost */
    private Boolean xgboost;

    /** python: model_exists */
    @JsonProperty("model_exists")
    private Boolean modelExists;

    /** python: modelVersion */
    private String modelVersion;

    /** python: error */
    private String error;

    /** ✅ Универсальный boolean-геттер. Старый код часто вызывает isOk(). */
    public boolean isOk() {
        return Boolean.TRUE.equals(ok);
    }

    /** ✅ Удобный хелпер: есть ли модель на диске. */
    public boolean hasModel() {
        return Boolean.TRUE.equals(modelExists);
    }

    /** ✅ Фабрика для контроллера/ошибок. */
    public static MlHealthResponse fail(String error) {
        return MlHealthResponse.builder()
                .ok(false)
                .ts(System.currentTimeMillis())
                .version(null)
                .modelsDir(null)
                .xgboost(false)
                .modelExists(false)
                .modelVersion(null)
                .error((error == null || error.isBlank()) ? "ml_unhealthy" : error)
                .build();
    }
}