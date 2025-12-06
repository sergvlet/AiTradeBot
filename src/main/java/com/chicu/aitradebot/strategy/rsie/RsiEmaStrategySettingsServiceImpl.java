package com.chicu.aitradebot.strategy.rsie;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RsiEmaStrategySettingsServiceImpl implements RsiEmaStrategySettingsService {

    private final RsiEmaStrategySettingsRepository repo;

    @Override
    public RsiEmaStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    RsiEmaStrategySettings def = RsiEmaStrategySettings.builder()
                            .chatId(chatId)
                            .symbol("BTCUSDT")
                            .rsiPeriod(14)
                            .emaFast(9)
                            .emaSlow(21)
                            .rsiBuyThreshold(30)
                            .rsiSellThreshold(70)
                            .timeframe("1m")
                            .candleLimit(200)
                            .build();

                    log.info("🆕 Созданы настройки RSI/EMA (chatId={})", chatId);
                    return repo.save(def);
                });
    }

    @Override
    public RsiEmaStrategySettings save(RsiEmaStrategySettings settings) {
        return repo.save(settings);
    }

    @Override
    public RsiEmaStrategySettings update(Long chatId, RsiEmaStrategySettings dto) {
        RsiEmaStrategySettings s = getOrCreate(chatId);

        if (dto.getSymbol() != null) s.setSymbol(dto.getSymbol());
        if (dto.getTimeframe() != null) s.setTimeframe(dto.getTimeframe());
        if (dto.getCandleLimit() > 0) s.setCandleLimit(dto.getCandleLimit());

        if (dto.getRsiPeriod() > 0) s.setRsiPeriod(dto.getRsiPeriod());
        if (dto.getEmaFast() > 0) s.setEmaFast(dto.getEmaFast());
        if (dto.getEmaSlow() > 0) s.setEmaSlow(dto.getEmaSlow());

        if (dto.getRsiBuyThreshold() > 0) s.setRsiBuyThreshold(dto.getRsiBuyThreshold());
        if (dto.getRsiSellThreshold() > 0) s.setRsiSellThreshold(dto.getRsiSellThreshold());

        return repo.save(s);
    }
}
