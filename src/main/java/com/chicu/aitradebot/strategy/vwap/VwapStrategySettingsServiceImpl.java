// src/main/java/com/chicu/aitradebot/strategy/vwap/VwapStrategySettingsServiceImpl.java
package com.chicu.aitradebot.strategy.vwap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VwapStrategySettingsServiceImpl implements VwapStrategySettingsService {

    private final VwapStrategySettingsRepository repo;

    @Override
    public VwapStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    VwapStrategySettings def = VwapStrategySettings.builder()
                            .chatId(chatId)
                            .build();
                    VwapStrategySettings saved = repo.save(def);
                    log.info("🆕 Созданы настройки VWAP (chatId={})", chatId);
                    return saved;
                });
    }

    @Override
    public VwapStrategySettings update(Long chatId, VwapStrategySettings incoming) {
        VwapStrategySettings cur = getOrCreate(chatId);

        if (incoming.getWindowCandles() != null) cur.setWindowCandles(incoming.getWindowCandles());
        if (incoming.getEntryDeviationPct() != null) cur.setEntryDeviationPct(incoming.getEntryDeviationPct());
        if (incoming.getExitDeviationPct() != null) cur.setExitDeviationPct(incoming.getExitDeviationPct());

        if (cur.getWindowCandles() == null || cur.getWindowCandles() < 5) cur.setWindowCandles(50);
        if (cur.getEntryDeviationPct() == null) cur.setEntryDeviationPct(0.30);
        if (cur.getExitDeviationPct() == null) cur.setExitDeviationPct(0.20);

        VwapStrategySettings saved = repo.save(cur);
        log.info("✅ VWAP settings saved (chatId={})", chatId);
        return saved;
    }
}
