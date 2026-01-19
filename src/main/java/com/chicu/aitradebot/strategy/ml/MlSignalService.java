// src/main/java/com/chicu/aitradebot/strategy/ai/MlSignalService.java
package com.chicu.aitradebot.strategy.ml;

public interface MlSignalService {

    /**
     * Возвращает прогноз (probBuy/probSell/confidence).
     * В реале тут будет вызов Python/XGBoost (HTTP/gRPC/Process).
     */
    MlPrediction predict(Long chatId, String symbol, String timeframe, MlFeatures features);
}
