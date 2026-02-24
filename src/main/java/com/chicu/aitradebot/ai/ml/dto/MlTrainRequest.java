package com.chicu.aitradebot.ai.ml.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class MlTrainRequest {

    private Long chatId;
    private String strategyType;
    private String symbol;
    private String timeframe;

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