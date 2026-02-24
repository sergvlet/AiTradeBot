package com.chicu.aitradebot.ai.ml.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
public class MlPredictRequest {

    private Long chatId;
    private String strategyType;
    private String symbol;
    private String timeframe;

    private String modelKey;
    private String schemaHash;

    private Map<String, Object> features;

    private Long tsMs;

    /**
     * Backward-compatible: старый стиль new MlPredictRequest(features)
     */
    public MlPredictRequest(Map<String, Object> features) {
        this.features = features;
    }
}
