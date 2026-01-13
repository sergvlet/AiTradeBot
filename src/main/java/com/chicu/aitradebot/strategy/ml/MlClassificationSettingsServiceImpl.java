// src/main/java/com/chicu/aitradebot/strategy/ml/MlClassificationSettingsServiceImpl.java
package com.chicu.aitradebot.strategy.ml;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlClassificationSettingsServiceImpl implements MlClassificationSettingsService {

    private final MlClassificationSettingsRepository repo;

    @Override
    public MlClassificationSettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    MlClassificationSettings def = MlClassificationSettings.builder()
                            .chatId(chatId)
                            .build();
                    MlClassificationSettings saved = repo.save(def);
                    log.info("[ML_CLASSIFICATION] 🆕 Созданы настройки (chatId={})", chatId);
                    return saved;
                });
    }

    @Override
    public MlClassificationSettings save(MlClassificationSettings s) {
        return repo.save(s);
    }
}
