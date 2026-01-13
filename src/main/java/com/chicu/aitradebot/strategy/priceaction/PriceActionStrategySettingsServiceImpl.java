package com.chicu.aitradebot.strategy.priceaction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceActionStrategySettingsServiceImpl implements PriceActionStrategySettingsService {

    private final PriceActionStrategySettingsRepository repo;

    @Override
    @Transactional
    public PriceActionStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    PriceActionStrategySettings def = PriceActionStrategySettings.builder()
                            .chatId(chatId)
                            .windowSize(120)
                            .minRangePct(0.35)
                            .breakoutOfRangePct(2.0)
                            .maxWickPctOfRange(55.0)
                            .confirmTicks(3)
                            .enabled(true)
                            .build();

                    PriceActionStrategySettings saved = repo.save(def);
                    log.info("🆕 Созданы настройки PRICE_ACTION (chatId={}, id={})", chatId, saved.getId());
                    return saved;
                });
    }

    @Override
    @Transactional
    public PriceActionStrategySettings update(Long chatId, PriceActionStrategySettings incoming) {
        PriceActionStrategySettings cur = getOrCreate(chatId);

        // windowSize: 20..20000
        if (incoming.getWindowSize() != null) {
            int v = incoming.getWindowSize();
            if (v < 20) v = 20;
            if (v > 20000) v = 20000;
            cur.setWindowSize(v);
        }

        // minRangePct: 0..50
        if (incoming.getMinRangePct() != null) {
            double v = incoming.getMinRangePct();
            if (v < 0.0) v = 0.0;
            if (v > 50.0) v = 50.0;
            cur.setMinRangePct(v);
        }

        // breakoutOfRangePct: 0..100
        if (incoming.getBreakoutOfRangePct() != null) {
            double v = incoming.getBreakoutOfRangePct();
            if (v < 0.0) v = 0.0;
            if (v > 100.0) v = 100.0;
            cur.setBreakoutOfRangePct(v);
        }

        // maxWickPctOfRange: 0..100
        if (incoming.getMaxWickPctOfRange() != null) {
            double v = incoming.getMaxWickPctOfRange();
            if (v < 0.0) v = 0.0;
            if (v > 100.0) v = 100.0;
            cur.setMaxWickPctOfRange(v);
        }

        // confirmTicks: 1..1000
        if (incoming.getConfirmTicks() != null) {
            int v = incoming.getConfirmTicks();
            if (v < 1) v = 1;
            if (v > 1000) v = 1000;
            cur.setConfirmTicks(v);
        }

        cur.setEnabled(incoming.isEnabled());

        PriceActionStrategySettings saved = repo.save(cur);
        log.info("✅ PRICE_ACTION настройки обновлены (chatId={}, id={})", chatId, saved.getId());
        return saved;
    }
}
