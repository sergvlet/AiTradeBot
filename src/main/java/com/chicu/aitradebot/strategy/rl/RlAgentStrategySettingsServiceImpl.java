// src/main/java/com/chicu/aitradebot/strategy/rl/RlAgentStrategySettingsServiceImpl.java
package com.chicu.aitradebot.strategy.rl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RlAgentStrategySettingsServiceImpl implements RlAgentStrategySettingsService {

    private final RlAgentStrategySettingsRepository repo;

    @Override
    public RlAgentStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    RlAgentStrategySettings def = RlAgentStrategySettings.builder()
                            .chatId(chatId)
                            .agentKey("default")
                            .minConfidence(0.55)
                            .decisionIntervalSeconds(5)
                            .build();
                    log.info("[RL_AGENT] 🆕 Created settings chatId={}", chatId);
                    return repo.save(def);
                });
    }
}
