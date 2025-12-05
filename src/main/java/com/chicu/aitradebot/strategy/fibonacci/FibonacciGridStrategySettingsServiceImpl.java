package com.chicu.aitradebot.strategy.fibonacci;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class FibonacciGridStrategySettingsServiceImpl
        implements FibonacciGridStrategySettingsService {

    private final FibonacciGridStrategySettingsRepository repo;

    @Override
    public FibonacciGridStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    FibonacciGridStrategySettings def = FibonacciGridStrategySettings.builder()
                            .chatId(chatId)
                            .symbol("BTCUSDT")
                            .gridLevels(6)
                            .distancePct(0.5)
                            .baseOrderVolume(20.0)
                            .timeframe("1m")
                            .candleLimit(200)
                            .build();

                    log.info("🆕 Созданы настройки Fibonacci Grid (chatId={})", chatId);
                    return repo.save(def);
                });
    }

    @Override
    public FibonacciGridStrategySettings save(FibonacciGridStrategySettings settings) {
        return repo.save(settings);
    }

    @Override
    public FibonacciGridStrategySettings update(Long chatId, FibonacciGridStrategySettings dto) {
        FibonacciGridStrategySettings s = getOrCreate(chatId);

        if (dto.getSymbol() != null) s.setSymbol(dto.getSymbol());
        if (dto.getGridLevels() > 0) s.setGridLevels(dto.getGridLevels());
        if (dto.getDistancePct() > 0) s.setDistancePct(dto.getDistancePct());
        if (dto.getBaseOrderVolume() > 0) s.setBaseOrderVolume(dto.getBaseOrderVolume());

        if (dto.getTimeframe() != null) s.setTimeframe(dto.getTimeframe());
        if (dto.getCandleLimit() > 0) s.setCandleLimit(dto.getCandleLimit());

        return repo.save(s);
    }
}
