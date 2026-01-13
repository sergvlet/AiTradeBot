package com.chicu.aitradebot.strategy.supportresistance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupportResistanceStrategySettingsServiceImpl
        implements SupportResistanceStrategySettingsService {

    private final SupportResistanceStrategySettingsRepository repo;

    @Override
    @Transactional
    public SupportResistanceStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    SupportResistanceStrategySettings def = SupportResistanceStrategySettings.builder()
                            .chatId(chatId)
                            .windowSize(240)
                            .minRangePct(0.35)
                            .entryFromSupportPct(0.15)
                            .breakoutAboveResistancePct(0.12)
                            .enabledBreakout(true)
                            .enabledBounce(true)
                            .build();

                    SupportResistanceStrategySettings saved = repo.save(def);
                    log.info("🆕 Созданы настройки SUPPORT_RESISTANCE (chatId={}, id={})", chatId, saved.getId());
                    return saved;
                });
    }

    @Override
    @Transactional
    public SupportResistanceStrategySettings update(Long chatId, SupportResistanceStrategySettings incoming) {

        SupportResistanceStrategySettings cur = getOrCreate(chatId);

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

        // entryFromSupportPct: 0..10
        if (incoming.getEntryFromSupportPct() != null) {
            double v = incoming.getEntryFromSupportPct();
            if (v < 0.0) v = 0.0;
            if (v > 10.0) v = 10.0;
            cur.setEntryFromSupportPct(v);
        }

        // breakoutAboveResistancePct: 0..10
        if (incoming.getBreakoutAboveResistancePct() != null) {
            double v = incoming.getBreakoutAboveResistancePct();
            if (v < 0.0) v = 0.0;
            if (v > 10.0) v = 10.0;
            cur.setBreakoutAboveResistancePct(v);
        }

        // toggles
        cur.setEnabledBreakout(incoming.isEnabledBreakout());
        cur.setEnabledBounce(incoming.isEnabledBounce());

        // sanity: если оба false — стратегия “мертвая”, включим bounce по умолчанию
        if (!cur.isEnabledBreakout() && !cur.isEnabledBounce()) {
            cur.setEnabledBounce(true);
        }

        SupportResistanceStrategySettings saved = repo.save(cur);
        log.info("✅ SUPPORT_RESISTANCE настройки обновлены (chatId={}, id={})", chatId, saved.getId());
        return saved;
    }
}
