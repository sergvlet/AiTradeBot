// src/main/java/com/chicu/aitradebot/strategy/hybrid/HybridStrategySettingsServiceImpl.java
package com.chicu.aitradebot.strategy.hybrid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class HybridStrategySettingsServiceImpl implements HybridStrategySettingsService {

    private final HybridStrategySettingsRepository repo;

    @Override
    @Transactional
    public HybridStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    HybridStrategySettings def = HybridStrategySettings.builder()
                            .chatId(chatId)
                            .build();
                    HybridStrategySettings saved = repo.save(def);
                    log.info("🆕 Созданы настройки HYBRID (chatId={})", chatId);
                    return saved;
                });
    }

    @Override
    @Transactional
    public HybridStrategySettings update(Long chatId, HybridStrategySettings incoming) {
        HybridStrategySettings cur = getOrCreate(chatId);

        if (incoming.getMlModelKey() != null && !incoming.getMlModelKey().isBlank()) {
            cur.setMlModelKey(incoming.getMlModelKey().trim());
        }
        if (incoming.getRlAgentKey() != null && !incoming.getRlAgentKey().isBlank()) {
            cur.setRlAgentKey(incoming.getRlAgentKey().trim());
        }

        if (incoming.getMinConfidence() != null) cur.setMinConfidence(incoming.getMinConfidence());
        if (incoming.getAllowSingleSourceBuy() != null) cur.setAllowSingleSourceBuy(incoming.getAllowSingleSourceBuy());

        if (incoming.getMlThreshold() != null) cur.setMlThreshold(incoming.getMlThreshold());
        if (incoming.getRlMinConfidence() != null) cur.setRlMinConfidence(incoming.getRlMinConfidence());
        if (incoming.getLookbackCandles() != null) cur.setLookbackCandles(incoming.getLookbackCandles());

        HybridStrategySettings saved = repo.save(cur);
        log.info("✅ HYBRID settings saved (chatId={})", chatId);
        return saved;
    }
}
