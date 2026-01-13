// src/main/java/com/chicu/aitradebot/strategy/fibonacci_grid/FibonacciGridStrategySettingsServiceImpl.java
package com.chicu.aitradebot.strategy.fibonacci_grid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class FibonacciGridStrategySettingsServiceImpl implements FibonacciGridStrategySettingsService {

    private final FibonacciGridStrategySettingsRepository repo;

    @Override
    public FibonacciGridStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    FibonacciGridStrategySettings def = FibonacciGridStrategySettings.builder()
                            .chatId(chatId)
                            .build();
                    FibonacciGridStrategySettings saved = repo.save(def);
                    log.info("🆕 Созданы настройки FIBONACCI_GRID (chatId={})", chatId);
                    return saved;
                });
    }

    @Override
    public FibonacciGridStrategySettings update(Long chatId, FibonacciGridStrategySettings incoming) {
        FibonacciGridStrategySettings cur = getOrCreate(chatId);

        if (incoming.getGridLevels() != null) cur.setGridLevels(incoming.getGridLevels());
        if (incoming.getDistancePct() != null) cur.setDistancePct(incoming.getDistancePct());
        // orderVolume допускаем null (значит объём берётся из глобальных/исполнителя)
        cur.setOrderVolume(incoming.getOrderVolume());

        if (cur.getGridLevels() == null || cur.getGridLevels() < 1) cur.setGridLevels(1);
        if (cur.getDistancePct() == null) cur.setDistancePct(new BigDecimal("0.5"));

        FibonacciGridStrategySettings saved = repo.save(cur);
        log.info("✅ FIBONACCI_GRID settings saved (chatId={})", chatId);
        return saved;
    }
}
