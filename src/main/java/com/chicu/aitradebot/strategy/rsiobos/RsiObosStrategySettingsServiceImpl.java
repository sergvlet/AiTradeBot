package com.chicu.aitradebot.strategy.rsiobos;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RsiObosStrategySettingsServiceImpl implements RsiObosStrategySettingsService {

    private final RsiObosStrategySettingsRepository repo;

    @Override
    @Transactional
    public RsiObosStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    RsiObosStrategySettings def = RsiObosStrategySettings.builder()
                            .chatId(chatId)
                            .rsiPeriod(14)
                            .buyBelow(30.0)
                            .blockAbove(70.0)
                            .spotLongOnly(true)
                            .build();

                    RsiObosStrategySettings saved = repo.save(def);
                    log.info("🆕 Созданы настройки RSI_OBOS (chatId={}, id={})", chatId, saved.getId());
                    return saved;
                });
    }

    @Override
    @Transactional
    public RsiObosStrategySettings update(Long chatId, RsiObosStrategySettings incoming) {

        RsiObosStrategySettings cur = getOrCreate(chatId);

        // rsiPeriod: 2..200 (разумные границы)
        if (incoming.getRsiPeriod() != null) {
            int v = incoming.getRsiPeriod();
            if (v < 2) v = 2;
            if (v > 200) v = 200;
            cur.setRsiPeriod(v);
        }

        // buyBelow: 1..50 (обычно 10..40)
        if (incoming.getBuyBelow() != null) {
            double v = incoming.getBuyBelow();
            if (v < 1.0) v = 1.0;
            if (v > 50.0) v = 50.0;
            cur.setBuyBelow(v);
        }

        // blockAbove: 50..99
        if (incoming.getBlockAbove() != null) {
            double v = incoming.getBlockAbove();
            if (v < 50.0) v = 50.0;
            if (v > 99.0) v = 99.0;
            cur.setBlockAbove(v);
        }

        // если кто-то выставил buyBelow >= blockAbove — разводим
        if (cur.getBuyBelow() != null && cur.getBlockAbove() != null) {
            if (cur.getBuyBelow() >= cur.getBlockAbove()) {
                // минимальный зазор
                cur.setBlockAbove(Math.min(99.0, cur.getBuyBelow() + 5.0));
            }
        }

        cur.setSpotLongOnly(incoming.isSpotLongOnly());

        RsiObosStrategySettings saved = repo.save(cur);
        log.info("✅ RSI_OBOS настройки обновлены (chatId={}, id={})", chatId, saved.getId());
        return saved;
    }
}
