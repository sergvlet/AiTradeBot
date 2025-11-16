package com.chicu.aitradebot.strategy.smartfusion;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.strategy.smartfusion.dto.SmartFusionUserSettingsDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SmartFusionStrategySettingsServiceImpl implements SmartFusionStrategySettingsService {

    private final SmartFusionStrategySettingsRepository repository;

    @Override
    public SmartFusionStrategySettings getOrCreate(Long chatId, String symbol) {
        return repository.findByChatIdAndSymbol(chatId, symbol)
                .orElseGet(() -> {

                    SmartFusionStrategySettings def = SmartFusionStrategySettings.builder()
                            .chatId(chatId)
                            .symbol(symbol)
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
                    log.info("🆕 Созданы настройки SmartFusion (chatId={}, symbol={})", chatId, symbol);
                    return saved;
                });
    }

    @Override
    public SmartFusionStrategySettings save(SmartFusionStrategySettings settings) {
        return repository.save(settings);
    }

    @Override
    @Transactional
    public SmartFusionStrategySettings updateUserParams(Long chatId, SmartFusionUserSettingsDto dto) {
        SmartFusionStrategySettings settings =
                repository.findByChatIdAndSymbol(chatId, dto.getSymbol())
                        .orElseGet(() -> getOrCreate(chatId, dto.getSymbol()));

        if (dto.getExchange() != null) settings.setExchange(dto.getExchange());
        if (dto.getNetworkType() != null) settings.setNetworkType(dto.getNetworkType());
        if (dto.getTimeframe() != null) settings.setTimeframe(dto.getTimeframe());
        if (dto.getCandleLimit() > 0) settings.setCandleLimit(dto.getCandleLimit());
        if (dto.getCapitalUsd() > 0) settings.setCapitalUsd(dto.getCapitalUsd());
        if (dto.getLeverage() > 0) settings.setLeverage(dto.getLeverage());
        if (dto.getRiskPerTradePct() > 0) settings.setRiskPerTradePct(dto.getRiskPerTradePct());
        if (dto.getDailyLossLimitPct() > 0) settings.setDailyLossLimitPct(dto.getDailyLossLimitPct());

        settings.setReinvestProfit(dto.isReinvestProfit());

        SmartFusionStrategySettings saved = repository.save(settings);

        log.info("⚙️ Обновлены SmartFusion параметры (chatId={}, symbol={})", chatId, dto.getSymbol());
        return saved;
    }

    /**
     * ⚠️Важно:
     * SmartFusion может хранить несколько символов.
     * Но стратегия или дашборд всегда работает с одним символом.

     * Мы возвращаем ПОСЛЕДНИЙ ИЗМЕНЁННЫЙ символ — а не просто рандом.
     */
    @Override
    public Optional<SmartFusionStrategySettings> findByChatId(Long chatId) {

        List<SmartFusionStrategySettings> list = repository.findAllByChatId(chatId);

        if (list.isEmpty())
            return Optional.empty();

        // сортируем по ID → последняя запись = последняя активная
        list.sort(Comparator.comparing(SmartFusionStrategySettings::getId).reversed());

        SmartFusionStrategySettings last = list.getFirst();

        log.debug("📌 findByChatId(chatId={}): выбран символ {}", chatId, last.getSymbol());

        return Optional.of(last);
    }

    @Override
    public Optional<SmartFusionStrategySettings> findByChatIdAndSymbol(Long chatId, String symbol) {
        return repository.findByChatIdAndSymbol(chatId, symbol);
    }
}
