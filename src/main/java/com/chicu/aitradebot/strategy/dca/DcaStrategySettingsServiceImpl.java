// src/main/java/com/chicu/aitradebot/strategy/dca/DcaStrategySettingsServiceImpl.java
package com.chicu.aitradebot.strategy.dca;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DcaStrategySettingsServiceImpl implements DcaStrategySettingsService {

    private final DcaStrategySettingsRepository repo;

    @Override
    @Transactional
    public DcaStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    DcaStrategySettings def = DcaStrategySettings.builder()
                            .chatId(chatId)
                            .intervalMinutes(60)
                            .build();
                    DcaStrategySettings saved = repo.save(def);
                    log.info("🆕 Созданы настройки DCA (chatId={})", chatId);
                    return saved;
                });
    }

    @Override
    @Transactional
    public DcaStrategySettings update(Long chatId, DcaStrategySettings incoming) {
        DcaStrategySettings cur = getOrCreate(chatId);

        if (incoming.getIntervalMinutes() != null) cur.setIntervalMinutes(incoming.getIntervalMinutes());
        if (incoming.getOrderVolume() != null) cur.setOrderVolume(incoming.getOrderVolume());

        // опционально
        if (incoming.getTakeProfitPct() != null) cur.setTakeProfitPct(incoming.getTakeProfitPct());
        if (incoming.getStopLossPct() != null) cur.setStopLossPct(incoming.getStopLossPct());

        DcaStrategySettings saved = repo.save(cur);
        log.info("✅ DCA settings saved (chatId={})", chatId);
        return saved;
    }
}
