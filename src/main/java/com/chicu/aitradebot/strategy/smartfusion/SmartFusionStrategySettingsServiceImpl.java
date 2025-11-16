package com.chicu.aitradebot.strategy.smartfusion;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.strategy.core.StrategySettingsProvider;
import com.chicu.aitradebot.strategy.smartfusion.dto.SmartFusionUserSettingsDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service("SMART_FUSION")
@RequiredArgsConstructor
public class SmartFusionStrategySettingsServiceImpl
        implements SmartFusionStrategySettingsService,
                   StrategySettingsProvider<SmartFusionStrategySettings> {

    private final SmartFusionStrategySettingsRepository repository;

    // =============================================================
    // StrategySettingsProvider — load()
    // =============================================================
    @Override
    public SmartFusionStrategySettings load(Long chatId) {
        return repository.findLatestByChatId(chatId).orElse(null);
    }

    // =============================================================
    // SmartFusionStrategySettingsService — getOrCreate()
    // =============================================================
    @Override
    public SmartFusionStrategySettings getOrCreate(Long chatId) {

        return repository.findLatestByChatId(chatId)
                .orElseGet(() -> {
                    SmartFusionStrategySettings def = SmartFusionStrategySettings.builder()
                            .chatId(chatId)
                            .symbol("BTCUSDT")
                            .exchange("BINANCE")
                            .networkType(NetworkType.TESTNET)
                            .timeframe("15m")
                            .candleLimit(200)
                            .commissionPct(0.1)
                            .capitalUsd(1000)
                            .riskPerTradePct(2.0)
                            .dailyLossLimitPct(3.0)
                            .emaFastPeriod(9)
                            .emaSlowPeriod(21)
                            .rsiPeriod(14)
                            .rsiBuyThreshold(45)
                            .rsiSellThreshold(55)
                            .bollingerPeriod(20)
                            .bollingerK(2.0)
                            .mlBuyMin(0.65)
                            .mlSellMin(0.55)
                            .takeProfitAtrMult(2.0)
                            .stopLossAtrMult(1.0)
                            .autoRetrain(false)
                            .reinvestProfit(true)
                            .build();

                    SmartFusionStrategySettings saved = repository.save(def);

                    log.info("🆕 Созданы дефолтные настройки SmartFusion (chatId={})", chatId);
                    return saved;
                });
    }

    // =============================================================
    @Override
    public SmartFusionStrategySettings save(SmartFusionStrategySettings settings) {
        return repository.save(settings);
    }

    // =============================================================
    // UPDATE PARAMS
    // =============================================================
    @Override
    @Transactional
    public SmartFusionStrategySettings updateUserParams(Long chatId, SmartFusionUserSettingsDto dto) {

        SmartFusionStrategySettings settings =
                repository.findLatestByChatId(chatId).orElseGet(() -> getOrCreate(chatId));

        // Создаём новую запись если изменён символ
        if (dto.getSymbol() != null && !dto.getSymbol().equals(settings.getSymbol())) {

            settings = SmartFusionStrategySettings.builder()
                    .chatId(chatId)
                    .symbol(dto.getSymbol())
                    .exchange(settings.getExchange())
                    .networkType(settings.getNetworkType())
                    .timeframe(settings.getTimeframe())
                    .candleLimit(settings.getCandleLimit())
                    .commissionPct(settings.getCommissionPct())
                    .capitalUsd(settings.getCapitalUsd())
                    .riskPerTradePct(settings.getRiskPerTradePct())
                    .dailyLossLimitPct(settings.getDailyLossLimitPct())
                    .emaFastPeriod(settings.getEmaFastPeriod())
                    .emaSlowPeriod(settings.getEmaSlowPeriod())
                    .rsiPeriod(settings.getRsiPeriod())
                    .rsiBuyThreshold(settings.getRsiBuyThreshold())
                    .rsiSellThreshold(settings.getRsiSellThreshold())
                    .bollingerPeriod(settings.getBollingerPeriod())
                    .bollingerK(settings.getBollingerK())
                    .mlBuyMin(settings.getMlBuyMin())
                    .mlSellMin(settings.getMlSellMin())
                    .takeProfitAtrMult(settings.getTakeProfitAtrMult())
                    .stopLossAtrMult(settings.getStopLossAtrMult())
                    .autoRetrain(settings.isAutoRetrain())
                    .reinvestProfit(settings.isReinvestProfit())
                    .build();
        }

        // apply changes
        if (dto.getExchange() != null) settings.setExchange(dto.getExchange());
        if (dto.getNetworkType() != null) settings.setNetworkType(dto.getNetworkType());
        if (dto.getTimeframe() != null) settings.setTimeframe(dto.getTimeframe());
        if (dto.getCandleLimit() > 0) settings.setCandleLimit(dto.getCandleLimit());
        if (dto.getCapitalUsd() > 0) settings.setCapitalUsd(dto.getCapitalUsd());
        if (dto.getRiskPerTradePct() > 0) settings.setRiskPerTradePct(dto.getRiskPerTradePct());
        if (dto.getDailyLossLimitPct() > 0) settings.setDailyLossLimitPct(dto.getDailyLossLimitPct());

        settings.setReinvestProfit(dto.isReinvestProfit());

        return repository.save(settings);
    }

    // =============================================================
    // ❗ правильная реализация findByChatId → Optional
    // =============================================================
    @Override
    public Optional<Object> findByChatId(Long chatId) {

        List<SmartFusionStrategySettings> all = repository.findAllByChatId(chatId);

        if (all.isEmpty()) return Optional.empty();

        return Optional.of(
                all.stream()
                        .max(Comparator.comparing(SmartFusionStrategySettings::getId))
                        .get()
        );
    }
}
