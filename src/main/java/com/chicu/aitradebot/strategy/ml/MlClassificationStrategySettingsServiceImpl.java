// src/main/java/com/chicu/aitradebot/strategy/ml/MlClassificationStrategySettingsServiceImpl.java
package com.chicu.aitradebot.strategy.ml;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlClassificationStrategySettingsServiceImpl implements MlClassificationStrategySettingsService {

    private final MlClassificationStrategySettingsRepository repo;

    @Override
    public MlClassificationStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    MlClassificationStrategySettings def = MlClassificationStrategySettings.builder()
                            .chatId(chatId)
                            .modelKey("default")
                            .minConfidence(0.60)
                            .lookbackCandles(200)
                            .build();
                    log.info("[ML_CLASSIFICATION] 🆕 Created settings chatId={}", chatId);
                    return repo.save(def);
                });
    }

    @Override
    public MlClassificationStrategySettings save(MlClassificationStrategySettings s) {
        return repo.save(s);
    }
}
