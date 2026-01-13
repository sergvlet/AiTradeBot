// src/main/java/com/chicu/aitradebot/strategy/ml/MlClassificationStrategySettingsService.java
package com.chicu.aitradebot.strategy.ml;

public interface MlClassificationStrategySettingsService {
    MlClassificationStrategySettings getOrCreate(Long chatId);
    MlClassificationStrategySettings save(MlClassificationStrategySettings s);
}
