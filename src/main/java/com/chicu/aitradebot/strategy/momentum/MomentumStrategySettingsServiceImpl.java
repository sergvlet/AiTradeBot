package com.chicu.aitradebot.strategy.momentum;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MomentumStrategySettingsServiceImpl implements MomentumStrategySettingsService {

    private final MomentumStrategySettingsRepository repo;

    @Override
    public MomentumStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    MomentumStrategySettings def = MomentumStrategySettings.builder()
                            .chatId(chatId)
                            .build();
                    MomentumStrategySettings saved = repo.save(def);
                    log.info("🆕 Созданы настройки MOMENTUM (chatId={})", chatId);
                    return saved;
                });
    }

    @Override
    @Transactional
    public MomentumStrategySettings update(Long chatId, MomentumStrategySettings incoming) {

        MomentumStrategySettings cur = getOrCreate(chatId);

        // lookbackBars >= 1
        if (incoming.getLookbackBars() != null) {
            int v = Math.max(1, incoming.getLookbackBars());
            cur.setLookbackBars(v);
        }

        // minPriceChangePct >= 0
        if (incoming.getMinPriceChangePct() != null) {
            double v = Math.max(0.0, incoming.getMinPriceChangePct());
            cur.setMinPriceChangePct(v);
        }

        // volumeToAverage >= 0
        if (incoming.getVolumeToAverage() != null) {
            double v = Math.max(0.0, incoming.getVolumeToAverage());
            cur.setVolumeToAverage(v);
        }

        // maxSpreadPct >= 0
        if (incoming.getMaxSpreadPct() != null) {
            double v = Math.max(0.0, incoming.getMaxSpreadPct());
            cur.setMaxSpreadPct(v);
        }

        // confirmBars >= 1
        if (incoming.getConfirmBars() != null) {
            int v = Math.max(1, incoming.getConfirmBars());
            cur.setConfirmBars(v);
        }

        MomentumStrategySettings saved = repo.save(cur);
        log.info("✅ MOMENTUM настройки обновлены (chatId={}, id={})", chatId, saved.getId());
        return saved;
    }

    @Override
    public MomentumStrategySettings save(MomentumStrategySettings s) {
        return repo.save(s);
    }
}
