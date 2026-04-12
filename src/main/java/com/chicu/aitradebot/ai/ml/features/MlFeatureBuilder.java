package com.chicu.aitradebot.ai.ml.features;

import com.chicu.aitradebot.common.enums.StrategyType;

import java.util.List;
import java.util.Map;

public interface MlFeatureBuilder {

    Map<String, Object> build(MlFeatureContext ctx);

    /**
     * ✅ Список фич + порядок (FeatureSpec).
     * Если вернёшь null/пусто — сборщик датасета отсортирует ключи сам.
     */
    default List<String> featureSpec(MlFeatureContext ctx) {
        return featureSpec(ctx != null ? ctx.getStrategyType() : null);
    }

    /**
     * ✅ Strategy-aware spec (опционально)
     */
    default List<String> featureSpec(StrategyType type) {
        return null;
    }

    /**
     * ✅ Версия схемы (для миграций моделей). Пока v1.
     */
    default String schemaVersion(StrategyType type) {
        return "v1";
    }
}