package com.chicu.aitradebot.strategy.scalping;

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
                            .build();  // все defaults подтянутся из @Builder.Default / @PrePersist

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
    // 3) Частичное обновление
    // =====================================================================
    @Override
    public ScalpingStrategySettings update(Long chatId, ScalpingStrategySettings dto) {

        ScalpingStrategySettings s = getOrCreate(chatId);

        // === УНИВЕРСАЛЬНЫЕ ПАРАМЕТРЫ ===

        if (dto.getSymbol() != null && !dto.getSymbol().isBlank())
            s.setSymbol(dto.getSymbol());

        if (dto.getTimeframe() != null && !dto.getTimeframe().isBlank())
            s.setTimeframe(dto.getTimeframe());

        if (dto.getCachedCandlesLimit() > 0)
            s.setCachedCandlesLimit(dto.getCachedCandlesLimit());

        if (dto.getCapitalUsd() > 0)
            s.setCapitalUsd(dto.getCapitalUsd());

        if (dto.getCommissionPct() > 0)
            s.setCommissionPct(dto.getCommissionPct());

        if (dto.getRiskPerTradePct() > 0)
            s.setRiskPerTradePct(dto.getRiskPerTradePct());

        if (dto.getDailyLossLimitPct() > 0)
            s.setDailyLossLimitPct(dto.getDailyLossLimitPct());

        if (dto.getLeverage() > 0)
            s.setLeverage(dto.getLeverage());

        s.setReinvestProfit(dto.isReinvestProfit());

        if (dto.getTakeProfitPct() > 0)
            s.setTakeProfitPct(dto.getTakeProfitPct());

        if (dto.getStopLossPct() > 0)
            s.setStopLossPct(dto.getStopLossPct());

        // === УНИКАЛЬНЫЕ ДЛЯ SCALPING ===

        if (dto.getWindowSize() > 0)
            s.setWindowSize(dto.getWindowSize());

        if (dto.getPriceChangeThreshold() > 0)
            s.setPriceChangeThreshold(dto.getPriceChangeThreshold());

        if (dto.getSpreadThreshold() > 0)
            s.setSpreadThreshold(dto.getSpreadThreshold());

        if (dto.getOrderVolume() > 0)
            s.setOrderVolume(dto.getOrderVolume());

        return repo.save(s);
    }
}
