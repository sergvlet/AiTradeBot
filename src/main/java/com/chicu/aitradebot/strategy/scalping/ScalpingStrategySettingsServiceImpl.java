package com.chicu.aitradebot.strategy.scalping;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScalpingStrategySettingsServiceImpl implements ScalpingStrategySettingsService {

    private final ScalpingStrategySettingsRepository repo;

    @Override
    public ScalpingStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {

                    // ⚠️ ВАЖНО: значения передаются ТОЛЬКО в реальные поля
                    ScalpingStrategySettings def = ScalpingStrategySettings.builder()
                            .chatId(chatId)
                            .symbol("BTCUSDT")
                            .timeframe("1m")
                            .windowSize(20)
                            .priceChangeThreshold(0.1)
                            .spreadThreshold(0.05)
                            .orderVolume(10.0)
                            .takeProfitPct(0.5)
                            .stopLossPct(0.3)
                            .cachedCandlesLimit(150)
                            .leverage(1)
                            .build();

                    log.info("🆕 Созданы настройки Scalping (chatId={})", chatId);
                    return repo.save(def);
                });
    }

    @Override
    public ScalpingStrategySettings save(ScalpingStrategySettings settings) {
        return repo.save(settings);
    }

    @Override
    public ScalpingStrategySettings update(Long chatId, ScalpingStrategySettings dto) {

        ScalpingStrategySettings s = getOrCreate(chatId);

        if (dto.getSymbol() != null)
            s.setSymbol(dto.getSymbol());

        if (dto.getTimeframe() != null)
            s.setTimeframe(dto.getTimeframe());

        if (dto.getWindowSize() > 0)
            s.setWindowSize(dto.getWindowSize());

        if (dto.getCachedCandlesLimit() > 0)
            s.setCachedCandlesLimit(dto.getCachedCandlesLimit());

        if (dto.getPriceChangeThreshold() > 0)
            s.setPriceChangeThreshold(dto.getPriceChangeThreshold());

        if (dto.getSpreadThreshold() > 0)
            s.setSpreadThreshold(dto.getSpreadThreshold());

        if (dto.getOrderVolume() > 0)
            s.setOrderVolume(dto.getOrderVolume());

        if (dto.getTakeProfitPct() > 0)
            s.setTakeProfitPct(dto.getTakeProfitPct());

        if (dto.getStopLossPct() > 0)
            s.setStopLossPct(dto.getStopLossPct());

        if (dto.getLeverage() > 0)
            s.setLeverage(dto.getLeverage());

        return repo.save(s);
    }
}
