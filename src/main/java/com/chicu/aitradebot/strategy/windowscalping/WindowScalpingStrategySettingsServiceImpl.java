package com.chicu.aitradebot.strategy.windowscalping;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WindowScalpingStrategySettingsServiceImpl implements WindowScalpingStrategySettingsService {

    private final WindowScalpingStrategySettingsRepository repo;

    @Override
    @Transactional
    public WindowScalpingStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    WindowScalpingStrategySettings def = WindowScalpingStrategySettings.builder()
                            .chatId(chatId)
                            .windowSize(30)
                            .entryFromLowPct(20.0)
                            .entryFromHighPct(20.0)
                            .minRangePct(0.25)
                            .maxSpreadPct(0.08)
                            .build();

                    WindowScalpingStrategySettings saved = repo.save(def);
                    log.info("🆕 Созданы настройки WINDOW_SCALPING (chatId={}, id={})", chatId, saved.getId());
                    return saved;
                });
    }

    @Override
    @Transactional
    public WindowScalpingStrategySettings update(Long chatId, WindowScalpingStrategySettings incoming) {

        WindowScalpingStrategySettings cur = getOrCreate(chatId);

        // windowSize: 5..1000 (окно в барах/тиках)
        if (incoming.getWindowSize() != null) {
            int v = incoming.getWindowSize();
            if (v < 5) v = 5;
            if (v > 1000) v = 1000;
            cur.setWindowSize(v);
        }

        // entryFromLowPct: 1..49
        if (incoming.getEntryFromLowPct() != null) {
            double v = incoming.getEntryFromLowPct();
            if (v < 1.0) v = 1.0;
            if (v > 49.0) v = 49.0;
            cur.setEntryFromLowPct(v);
        }

        // entryFromHighPct: 1..49
        if (incoming.getEntryFromHighPct() != null) {
            double v = incoming.getEntryFromHighPct();
            if (v < 1.0) v = 1.0;
            if (v > 49.0) v = 49.0;
            cur.setEntryFromHighPct(v);
        }

        // minRangePct: 0.01..50
        if (incoming.getMinRangePct() != null) {
            double v = incoming.getMinRangePct();
            if (v < 0.01) v = 0.01;
            if (v > 50.0) v = 50.0;
            cur.setMinRangePct(v);
        }

        // maxSpreadPct: 0..10
        if (incoming.getMaxSpreadPct() != null) {
            double v = incoming.getMaxSpreadPct();
            if (v < 0.0) v = 0.0;
            if (v > 10.0) v = 10.0;
            cur.setMaxSpreadPct(v);
        }

        // sanity: lowPct + highPct <= 95 (чтобы оставался “центр”)
        if (cur.getEntryFromLowPct() != null && cur.getEntryFromHighPct() != null) {
            double sum = cur.getEntryFromLowPct() + cur.getEntryFromHighPct();
            if (sum > 95.0) {
                // мягко подрежем верх, сохранив низ
                cur.setEntryFromHighPct(Math.max(1.0, 95.0 - cur.getEntryFromLowPct()));
            }
        }

        WindowScalpingStrategySettings saved = repo.save(cur);
        log.info("✅ WINDOW_SCALPING настройки обновлены (chatId={}, id={})", chatId, saved.getId());
        return saved;
    }
}
