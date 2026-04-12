package com.chicu.aitradebot.strategy.ml;


import com.chicu.aitradebot.ai.ml.dto.MlPrediction;

import java.util.Map;

public interface MlSignalService {

    boolean isAvailable();

    MlPrediction predict(Long chatId,
                         String symbol,
                         String timeframe,
                         String modelKey,
                         String schemaHash,
                         Map<String, Object> features);
}
