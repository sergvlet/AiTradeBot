package com.chicu.aitradebot.strategy.volatilitybreakout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VolatilityBreakoutStrategySettingsServiceImpl
        implements VolatilityBreakoutStrategySettingsService {

    private final VolatilityBreakoutStrategySettingsRepository repo;

    @Override
    public VolatilityBreakoutStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    VolatilityBreakoutStrategySettings def = VolatilityBreakoutStrategySettings.builder()
                            .chatId(chatId)
                            .build();
                    log.info("🆕 Созданы настройки VOLATILITY_BREAKOUT (chatId={})", chatId);
                    return repo.save(def);
                });
    }

    @Override
    public VolatilityBreakoutStrategySettings save(VolatilityBreakoutStrategySettings s) {
        return repo.save(s);
    }
}
