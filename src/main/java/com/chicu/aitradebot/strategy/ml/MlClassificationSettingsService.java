// src/main/java/com/chicu/aitradebot/strategy/ml/MlClassificationSettingsService.java
package com.chicu.aitradebot.strategy.ml;

public interface MlClassificationSettingsService {

    MlClassificationSettings getOrCreate(Long chatId);

    MlClassificationSettings save(MlClassificationSettings s);
}
