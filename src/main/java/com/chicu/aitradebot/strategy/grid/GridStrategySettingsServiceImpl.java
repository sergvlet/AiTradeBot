// src/main/java/com/chicu/aitradebot/strategy/grid/GridStrategySettingsServiceImpl.java
package com.chicu.aitradebot.strategy.grid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class GridStrategySettingsServiceImpl implements GridStrategySettingsService {

    private final GridStrategySettingsRepository repo;

    @Override
    public GridStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    GridStrategySettings def = GridStrategySettings.builder()
                            .chatId(chatId)
                            .build();
                    GridStrategySettings saved = repo.save(def);
                    log.info("🆕 Созданы настройки GRID (chatId={})", chatId);
                    return saved;
                });
    }

    @Override
    public GridStrategySettings update(Long chatId, GridStrategySettings incoming) {
        GridStrategySettings cur = getOrCreate(chatId);

        if (incoming.getGridLevels() != null) cur.setGridLevels(incoming.getGridLevels());
        if (incoming.getGridStepPct() != null) cur.setGridStepPct(incoming.getGridStepPct());
        if (incoming.getOrderVolume() != null) cur.setOrderVolume(incoming.getOrderVolume());

        // защита от null/пустых
        if (cur.getGridLevels() == null || cur.getGridLevels() < 1) cur.setGridLevels(1);
        if (cur.getGridStepPct() == null) cur.setGridStepPct(new BigDecimal("0.50000000"));
        if (cur.getOrderVolume() == null) cur.setOrderVolume(new BigDecimal("20.00000000"));

        GridStrategySettings saved = repo.save(cur);
        log.info("✅ GRID settings saved (chatId={})", chatId);
        return saved;
    }
}
