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

    // =====================================================================
    // GET OR CREATE
    // =====================================================================
    @Override
    public FibonacciGridStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {

                    FibonacciGridStrategySettings def =
                            FibonacciGridStrategySettings.builder()
                                    .chatId(chatId)
                                    .build(); // все дефолты через @Builder.Default

                    log.info("🆕 Созданы настройки Fibonacci Grid (chatId={})", chatId);
                    return repo.save(def);
                });
    }

    // =====================================================================
    // SAVE (прямое сохранение)
    // =====================================================================
    @Override
    public FibonacciGridStrategySettings save(FibonacciGridStrategySettings settings) {
        return repo.save(settings);
    }

    // =====================================================================
    // UPDATE (частичное обновление DTO)
    // =====================================================================
    @Override
    public FibonacciGridStrategySettings update(Long chatId,
                                                FibonacciGridStrategySettings dto) {

        FibonacciGridStrategySettings s = getOrCreate(chatId);

        // ===== UNIVERSAL (пока ограничены тем, что есть в сущности) =====

        if (dto.getSymbol() != null && !dto.getSymbol().isBlank())
            s.setSymbol(dto.getSymbol().toUpperCase());

        if (dto.getTimeframe() != null && !dto.getTimeframe().isBlank())
            s.setTimeframe(dto.getTimeframe());

        if (dto.getCandleLimit() > 0)
            s.setCandleLimit(dto.getCandleLimit());

        if (dto.getNetworkType() != null)
            s.setNetworkType(dto.getNetworkType());

        s.setActive(dto.isActive());

        // ===== UNIQUE FIB GRID FIELDS =====

        if (dto.getGridLevels() > 0)
            s.setGridLevels(dto.getGridLevels());

        if (dto.getDistancePct() > 0)
            s.setDistancePct(dto.getDistancePct());

        if (dto.getBaseOrderVolume() > 0)
            s.setBaseOrderVolume(dto.getBaseOrderVolume());

        if (dto.getTakeProfitPct() > 0)
            s.setTakeProfitPct(dto.getTakeProfitPct());

        if (dto.getStopLossPct() > 0)
            s.setStopLossPct(dto.getStopLossPct());

        return repo.save(s);
    }
    @Override
    public FibonacciGridStrategySettings getLatest(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseThrow(() -> new IllegalStateException(
                        "Нет настроек FibonacciGrid для chatId=" + chatId));
    }

}
