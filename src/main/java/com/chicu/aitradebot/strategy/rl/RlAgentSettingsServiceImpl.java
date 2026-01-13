// src/main/java/com/chicu/aitradebot/strategy/rl/RlAgentSettingsServiceImpl.java
package com.chicu.aitradebot.strategy.rl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RlAgentSettingsServiceImpl implements RlAgentSettingsService {

    private final RlAgentSettingsRepository repo;

    @Override
    public RlAgentSettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    RlAgentSettings def = RlAgentSettings.builder()
                            .chatId(chatId)
                            .enabled(true)
                            .build();

                    RlAgentSettings saved = repo.save(def);
                    log.info("[RL_AGENT] 🆕 Созданы настройки RL_AGENT (chatId={})", chatId);
                    return saved;
                });
    }

    @Override
    public RlAgentSettings save(RlAgentSettings s) {
        return repo.save(s);
    }
}
