package com.chicu.aitradebot.strategy.scalping;

import com.chicu.aitradebot.strategy.core.SettingsSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScalpingStrategySettingsServiceImpl implements ScalpingStrategySettingsService {

    private final ScalpingStrategySettingsRepository repo;

    // =====================================================================
    // 1) Получение или создание настроек
    // =====================================================================
    @Override
    public ScalpingStrategySettings getOrCreate(Long chatId) {

        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {

                    ScalpingStrategySettings def = ScalpingStrategySettings.builder()
                            .chatId(chatId)
                            .build();

                    log.info("🆕 Созданы настройки SCALPING (chatId={})", chatId);
                    return repo.save(def);
                });
    }

    // =====================================================================
    // 2) Сохранение
    // =====================================================================
    @Override
    public ScalpingStrategySettings save(ScalpingStrategySettings settings) {
        return repo.save(settings);
    }

    // =====================================================================
    // 3) Частичное обновление (ТОЛЬКО АКТУАЛЬНЫЕ ПОЛЯ)
    // =====================================================================
    @Override
    public ScalpingStrategySettings update(Long chatId, ScalpingStrategySettings dto) {

        ScalpingStrategySettings s = getOrCreate(chatId);

        // === БАЗОВЫЕ ПАРАМЕТРЫ ===

        if (dto.getSymbol() != null && !dto.getSymbol().isBlank()) {
            s.setSymbol(dto.getSymbol());
        }

        if (dto.getTimeframe() != null && !dto.getTimeframe().isBlank()) {
            s.setTimeframe(dto.getTimeframe());
        }

        if (dto.getCachedCandlesLimit() > 0) {
            s.setCachedCandlesLimit(dto.getCachedCandlesLimit());
        }

        // === SCALPING-ПАРАМЕТРЫ ===

        if (dto.getWindowSize() > 0) {
            s.setWindowSize(dto.getWindowSize());
        }

        if (dto.getPriceChangeThreshold() > 0) {
            s.setPriceChangeThreshold(dto.getPriceChangeThreshold());
        }

        if (dto.getSpreadThreshold() > 0) {
            s.setSpreadThreshold(dto.getSpreadThreshold());
        }

        if (dto.getTakeProfitPct() > 0) {
            s.setTakeProfitPct(dto.getTakeProfitPct());
        }

        if (dto.getStopLossPct() > 0) {
            s.setStopLossPct(dto.getStopLossPct());
        }

        // ⚠️ orderVolume: double в entity
        if (dto.getOrderVolume().signum() > 0) {
            s.setOrderVolume(dto.getOrderVolume().doubleValue());
        }

        return repo.save(s);
    }

    // =====================================================================
    // 4) Snapshot (ПОКА МОЖНО ПРОСТО NULL)
    // =====================================================================
    @Override
    public SettingsSnapshot getSnapshot(long chatId) {
        return null;
    }
}
