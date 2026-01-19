// src/main/java/com/chicu/aitradebot/strategy/volume/VolumeProfileStrategySettingsServiceImpl.java
package com.chicu.aitradebot.strategy.volume;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class VolumeProfileStrategySettingsServiceImpl implements VolumeProfileStrategySettingsService {

    private final VolumeProfileStrategySettingsRepository repo;

    @Override
    public VolumeProfileStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    VolumeProfileStrategySettings def = VolumeProfileStrategySettings.builder()
                            .chatId(chatId)
                            .build();
                    VolumeProfileStrategySettings saved = repo.save(def);
                    log.info("🆕 Созданы настройки VOLUME_PROFILE (chatId={})", chatId);
                    return saved;
                });
    }

    @Override
    public VolumeProfileStrategySettings update(Long chatId, VolumeProfileStrategySettings incoming) {
        VolumeProfileStrategySettings cur = getOrCreate(chatId);

        // lookbackCandles может быть null (и это ок)
        cur.setLookbackCandles(incoming.getLookbackCandles());

        if (incoming.getBins() != null) cur.setBins(incoming.getBins());
        if (incoming.getValueAreaPct() != null) cur.setValueAreaPct(incoming.getValueAreaPct());
        if (incoming.getEntryMode() != null) cur.setEntryMode(incoming.getEntryMode());

        if (cur.getBins() == null || cur.getBins() < 8) cur.setBins(48);
        if (cur.getValueAreaPct() == null) cur.setValueAreaPct(new BigDecimal("70"));
        if (cur.getEntryMode() == null) cur.setEntryMode(VolumeProfileStrategySettings.EntryMode.MEAN_REVERT);

        VolumeProfileStrategySettings saved = repo.save(cur);
        log.info("✅ VOLUME_PROFILE settings saved (chatId={})", chatId);
        return saved;
    }
}
