package com.chicu.aitradebot.strategy.breakout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BreakoutStrategySettingsServiceImpl implements BreakoutStrategySettingsService {

    private final BreakoutStrategySettingsRepository repo;

    @Override
    @Transactional
    public BreakoutStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    BreakoutStrategySettings def = BreakoutStrategySettings.builder()
                            .chatId(chatId)
                            .rangeLookback(50)
                            .breakoutBufferPct(0.08)
                            .minRangePct(0.25)
                            .build();

                    BreakoutStrategySettings saved = repo.save(def);
                    log.info("🆕 Созданы настройки BREAKOUT (chatId={}, id={})", chatId, saved.getId());
                    return saved;
                });
    }

    @Override
    @Transactional
    public BreakoutStrategySettings update(Long chatId, BreakoutStrategySettings incoming) {

        BreakoutStrategySettings cur = getOrCreate(chatId);

        // rangeLookback: 5..2000
        if (incoming.getRangeLookback() != null) {
            int v = incoming.getRangeLookback();
            if (v < 5) v = 5;
            if (v > 2000) v = 2000;
            cur.setRangeLookback(v);
        }

        // breakoutBufferPct: 0..10
        if (incoming.getBreakoutBufferPct() != null) {
            double v = incoming.getBreakoutBufferPct();
            if (v < 0.0) v = 0.0;
            if (v > 10.0) v = 10.0;
            cur.setBreakoutBufferPct(v);
        }

        // minRangePct: 0.01..50
        if (incoming.getMinRangePct() != null) {
            double v = incoming.getMinRangePct();
            if (v < 0.01) v = 0.01;
            if (v > 50.0) v = 50.0;
            cur.setMinRangePct(v);
        }

        // sanity: буфер пробоя не должен быть больше minRangePct (иначе почти всегда будет “нет пробоя”)
        if (cur.getBreakoutBufferPct() != null && cur.getMinRangePct() != null) {
            if (cur.getBreakoutBufferPct() > cur.getMinRangePct()) {
                cur.setBreakoutBufferPct(cur.getMinRangePct());
            }
        }

        BreakoutStrategySettings saved = repo.save(cur);
        log.info("✅ BREAKOUT настройки обновлены (chatId={}, id={})", chatId, saved.getId());
        return saved;
    }
}
