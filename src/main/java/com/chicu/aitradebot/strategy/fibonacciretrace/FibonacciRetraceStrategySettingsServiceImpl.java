package com.chicu.aitradebot.strategy.fibonacciretrace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FibonacciRetraceStrategySettingsServiceImpl
        implements FibonacciRetraceStrategySettingsService {

    private final FibonacciRetraceStrategySettingsRepository repo;

    @Override
    @Transactional
    public FibonacciRetraceStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    FibonacciRetraceStrategySettings def = FibonacciRetraceStrategySettings.builder()
                            .chatId(chatId)
                            .windowSize(240)
                            .minRangePct(0.45)
                            .entryLevel(0.618)
                            .entryTolerancePct(0.10)
                            .invalidateBelowLowPct(0.15)
                            .enabled(true)
                            .build();

                    FibonacciRetraceStrategySettings saved = repo.save(def);
                    log.info("🆕 Созданы настройки FIBONACCI_RETRACE (chatId={}, id={})", chatId, saved.getId());
                    return saved;
                });
    }

    @Override
    @Transactional
    public FibonacciRetraceStrategySettings update(Long chatId, FibonacciRetraceStrategySettings incoming) {

        FibonacciRetraceStrategySettings cur = getOrCreate(chatId);

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

        // entryLevel: 0..1 (фибо-коэффициент)
        if (incoming.getEntryLevel() != null) {
            double v = incoming.getEntryLevel();
            if (v < 0.0) v = 0.0;
            if (v > 1.0) v = 1.0;
            cur.setEntryLevel(v);
        }

        // entryTolerancePct: 0..10 (% от цены)
        if (incoming.getEntryTolerancePct() != null) {
            double v = incoming.getEntryTolerancePct();
            if (v < 0.0) v = 0.0;
            if (v > 10.0) v = 10.0;
            cur.setEntryTolerancePct(v);
        }

        // invalidateBelowLowPct: 0..20
        if (incoming.getInvalidateBelowLowPct() != null) {
            double v = incoming.getInvalidateBelowLowPct();
            if (v < 0.0) v = 0.0;
            if (v > 20.0) v = 20.0;
            cur.setInvalidateBelowLowPct(v);
        }

        cur.setEnabled(incoming.isEnabled());

        FibonacciRetraceStrategySettings saved = repo.save(cur);
        log.info("✅ FIBONACCI_RETRACE настройки обновлены (chatId={}, id={})", chatId, saved.getId());
        return saved;
    }
}
