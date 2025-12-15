package com.chicu.aitradebot.strategy.rsie;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RsiEmaStrategySettingsServiceImpl implements RsiEmaStrategySettingsService {

    private final RsiEmaStrategySettingsRepository repo;

    // =====================================================================
    // GET OR CREATE
    // =====================================================================
    @Override
    public RsiEmaStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {

                    RsiEmaStrategySettings def = RsiEmaStrategySettings.builder()
                            .chatId(chatId)
                            .symbol("BTCUSDT")
                            .timeframe("1m")
                            .cachedCandlesLimit(150)

                            // RSI / EMA defaults
                            .rsiPeriod(14)
                            .emaFast(9)
                            .emaSlow(21)
                            .rsiBuyThreshold(30.0)
                            .rsiSellThreshold(70.0)

                            // Risk & capital defaults
                            .capitalUsd(50.0)
                            .commissionPct(0.04)
                            .riskPerTradePct(1.0)
                            .dailyLossLimitPct(5.0)
                            .takeProfitPct(0.5)
                            .stopLossPct(0.5)
                            .leverage(1)
                            .reinvestProfit(false)

                            .build();

                    log.info("🆕 Созданы настройки RSI/EMA (chatId={})", chatId);
                    return repo.save(def);
                });
    }

    // =====================================================================
    // SAVE
    // =====================================================================
    @Override
    public RsiEmaStrategySettings save(RsiEmaStrategySettings settings) {
        return repo.save(settings);
    }

    // =====================================================================
    // UPDATE — частичное обновление только переданных полей
    // =====================================================================
    @Override
    public RsiEmaStrategySettings update(Long chatId, RsiEmaStrategySettings dto) {

        RsiEmaStrategySettings s = getOrCreate(chatId);

        // ===== COMMON (унифицированные) =====
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

        s.setReinvestProfit(dto.isReinvestProfit());

        if (dto.getLeverage() > 0)
            s.setLeverage(dto.getLeverage());

        if (dto.getTakeProfitPct() > 0)
            s.setTakeProfitPct(dto.getTakeProfitPct());

        if (dto.getStopLossPct() > 0)
            s.setStopLossPct(dto.getStopLossPct());

        // ===== RSI + EMA =====
        if (dto.getRsiPeriod() > 0)
            s.setRsiPeriod(dto.getRsiPeriod());

        if (dto.getEmaFast() > 0)
            s.setEmaFast(dto.getEmaFast());

        if (dto.getEmaSlow() > 0)
            s.setEmaSlow(dto.getEmaSlow());

        if (dto.getRsiBuyThreshold() > 0)
            s.setRsiBuyThreshold(dto.getRsiBuyThreshold());

        if (dto.getRsiSellThreshold() > 0)
            s.setRsiSellThreshold(dto.getRsiSellThreshold());

        return repo.save(s);
    }
}
