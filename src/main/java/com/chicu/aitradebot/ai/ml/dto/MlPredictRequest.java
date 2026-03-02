package com.chicu.aitradebot.ai.ml.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MlPredictRequest {

    private Long chatId;
    private String strategyType;
    private String symbol;
    private String timeframe;

    private String modelKey;
    private String schemaHash;

    /**
     * ✅ Явный порядок фич (если нужен).
     * Sidecar принимает: featureOrder / schema / schemaFields.
     */
    private List<String> featureOrder;

    private Map<String, Object> features;

    private Long tsMs;

    /**
     * Backward-compatible: старый стиль new MlPredictRequest(features)
     */
    public MlPredictRequest(Map<String, Object> features) {
        this.features = features;
    }
}