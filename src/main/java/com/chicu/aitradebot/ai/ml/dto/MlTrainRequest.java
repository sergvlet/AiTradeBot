package com.chicu.aitradebot.ai.ml.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MlTrainRequest {

    private Long chatId;
    private String strategyType;
    private String symbol;
    private String timeframe;

    /**
     * ✅ Явный ключ модели.
     * Если null — sidecar соберёт ключ автоматически: strategyType:symbol:timeframe
     */
    private String modelKey;

    /**
     * schemaHash = “версия схемы фич”
     * чтобы не обучать/не предсказывать на несовместимых данных
     */
    private String schemaHash;

    /**
     * Явный порядок фичей (очень желательно на прод).
     * Если null — ML сервис сам выведет schema из rows (по ключам, отсортирует).
     */
    private List<String> featureSchema;

    /**
     * Сэмплы: features + label.
     *
     * ВАЖНО: label должен быть в каждом row одним из ключей:
     *  - "y" (предпочтительно)
     *  - "label"
     *  - "target"
     *  - "class"
     *  - "win"
     *
     * Остальные ключи — это фичи.
     * META ключи (chatId/symbol/timeframe/modelKey/schemaHash/tsMs/strategyType) игнорируются ML сервисом.
     */
    private List<Map<String, Object>> rows;

    /**
     * Гиперпараметры (опционально)
     */
    private Map<String, Object> params;
}