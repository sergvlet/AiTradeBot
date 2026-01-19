package com.chicu.aitradebot.strategy.ema;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmaCrossoverStrategySettingsServiceImpl implements EmaCrossoverStrategySettingsService {

    private final EmaCrossoverStrategySettingsRepository repo;

    @Override
    @Transactional
    public EmaCrossoverStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    EmaCrossoverStrategySettings def = EmaCrossoverStrategySettings.builder()
                            .chatId(chatId)
                            .build();
                    EmaCrossoverStrategySettings saved = repo.save(def);
                    log.info("🆕 Созданы настройки EMA_CROSSOVER (chatId={}, id={})", chatId, saved.getId());
                    return saved;
                });
    }

    @Override
    @Transactional
    public EmaCrossoverStrategySettings update(Long chatId, EmaCrossoverStrategySettings incoming) {

        EmaCrossoverStrategySettings cur = getOrCreate(chatId);

        // emaFast >= 1
        if (incoming.getEmaFast() != null) {
            cur.setEmaFast(Math.max(1, incoming.getEmaFast()));
        }

        // emaSlow >= 1 и >= emaFast
        if (incoming.getEmaSlow() != null) {
            int slow = Math.max(1, incoming.getEmaSlow());
            int fast = cur.getEmaFast() != null ? cur.getEmaFast() : 1;
            if (slow < fast) slow = fast;
            cur.setEmaSlow(slow);
        }

        // confirmBars >= 1
        if (incoming.getConfirmBars() != null) {
            cur.setConfirmBars(Math.max(1, incoming.getConfirmBars()));
        }

        // maxSpreadPct >= 0 (ограничим, чтобы не было 9999)
        if (incoming.getMaxSpreadPct() != null) {
            double v = Math.max(0.0, incoming.getMaxSpreadPct());
            if (v > 100.0) v = 100.0;
            cur.setMaxSpreadPct(v);
        }

        EmaCrossoverStrategySettings saved = repo.save(cur);
        log.info("✅ EMA_CROSSOVER настройки обновлены (chatId={}, id={})", chatId, saved.getId());
        return saved;
    }
}
