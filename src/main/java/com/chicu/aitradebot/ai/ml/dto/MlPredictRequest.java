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
     * Основной порядок фич.
     */
    private List<String> featureOrder;

    /**
     * Зеркала для совместимости с sidecar.
     */
    private List<String> schema;
    private List<String> schemaFields;

    private Map<String, Object> features;

    private Long tsMs;

    /**
     * Backward-compatible: старый стиль new MlPredictRequest(features)
     */
    public MlPredictRequest(Map<String, Object> features) {
        this.features = features;
    }

    /**
     * При установке featureOrder сразу синхронизируем совместимые поля.
     */
    public void setFeatureOrder(List<String> featureOrder) {
        this.featureOrder = featureOrder;
        this.schema = featureOrder;
        this.schemaFields = featureOrder;
    }

    /**
     * Если кто-то установил schema напрямую — тоже синхронизируем всё.
     */
    public void setSchema(List<String> schema) {
        this.schema = schema;
        this.featureOrder = schema;
        this.schemaFields = schema;
    }

    /**
     * Если кто-то установил schemaFields напрямую — тоже синхронизируем всё.
     */
    public void setSchemaFields(List<String> schemaFields) {
        this.schemaFields = schemaFields;
        this.featureOrder = schemaFields;
        this.schema = schemaFields;
    }
}