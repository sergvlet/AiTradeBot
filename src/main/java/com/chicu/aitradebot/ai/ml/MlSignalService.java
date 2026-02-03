package com.chicu.aitradebot.ai.ml;

/**
 * Контракт “получить прогноз”.
 * Реализация будет HTTP (через MlClient) и будет ходить в Python sidecar.
 */
public interface MlSignalService {

    /**
     * Быстрая проверка доступности ML (кэшируй внутри реализации, чтобы не спамить /health).
     */
    boolean isAvailable();

    /**
     * @param modelKey ключ модели (например "xgb-v1"), может быть null -> берём default на стороне Python/или Java
     */
    MlPrediction predict(Long chatId, String symbol, String timeframe, String modelKey, MlFeatures features);
}
