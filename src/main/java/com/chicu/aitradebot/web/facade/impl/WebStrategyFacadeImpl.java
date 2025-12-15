package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;
import com.chicu.aitradebot.repository.StrategySettingsRepository;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.web.facade.StrategyUi;
import com.chicu.aitradebot.web.facade.WebStrategyFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebStrategyFacadeImpl implements WebStrategyFacade {

    private final StrategySettingsRepository settingsRepo;
    private final StrategySettingsService settingsService;
    private final AiStrategyOrchestrator orchestrator;

    // =====================================================================
    // 📋 UI список стратегий
    // =====================================================================
    @Override
    public List<StrategyUi> getStrategies(Long chatId) {

        List<StrategySettings> list = settingsRepo.findByChatId(chatId);

        if (list.isEmpty()) {
            log.warn("⚠ strategy_settings пустая → создаём дефолтные, chatId={}", chatId);
            createDefaultStrategies(chatId);
            list = settingsRepo.findByChatId(chatId);
        }

        List<StrategyUi> result = new ArrayList<>();

        for (StrategySettings s : list) {

            double pnl = s.getTotalProfitPct() != null ? s.getTotalProfitPct().doubleValue() : 0.0;
            double conf = s.getMlConfidence() != null ? s.getMlConfidence().doubleValue() : 0.0;

            NetworkType network = s.getNetworkType() != null ? s.getNetworkType() : NetworkType.TESTNET;

            result.add(new StrategyUi(
                    s.getType(),
                    s.isActive(),
                    getTitle(s.getType()),
                    getDescription(s.getType()),
                    s.getChatId(),
                    s.getSymbol(),
                    pnl,
                    conf,
                    network
            ));
        }

        result.sort(
                Comparator.comparing(StrategyUi::active).reversed()
                        .thenComparing(StrategyUi::totalProfitPct, Comparator.reverseOrder())
        );

        return result;
    }

    // =====================================================================
    // 🔁 toggle (старый API)
    // =====================================================================
    @Override
    public void toggle(Long chatId, StrategyType strategyType) {
        StrategySettings s = settingsService.getOrCreate(chatId, strategyType);

        if (s.isActive()) {
            orchestrator.stopStrategy(chatId, strategyType);
        } else {
            orchestrator.startStrategy(chatId, strategyType);
        }
    }

    // =====================================================================
    // ▶ start
    // =====================================================================
    @Override
    public void start(Long chatId, StrategyType strategyType) {
        orchestrator.startStrategy(chatId, strategyType);
    }

    // =====================================================================
    // ⏹ stop
    // =====================================================================
    @Override
    public void stop(Long chatId, StrategyType strategyType) {
        orchestrator.stopStrategy(chatId, strategyType);
    }

    // =====================================================================
    // ⭐ Новый API: toggleStrategy (WEB UI)
    // =====================================================================
    @Override
    public StrategyRunInfo toggleStrategy(Long chatId,
                                          StrategyType strategyType,
                                          String symbol,
                                          String timeframe) {

        StrategySettings s = settingsService.getOrCreate(chatId, strategyType);

        if (symbol != null && !symbol.isBlank())
            s.setSymbol(symbol);

        if (timeframe != null && !timeframe.isBlank())
            s.setTimeframe(timeframe);

        settingsService.save(s);

        return (s.isActive())
                ? orchestrator.stopStrategy(chatId, strategyType)
                : orchestrator.startStrategy(chatId, strategyType);
    }

    // =====================================================================
    // 📌 RunInfo
    // =====================================================================
    @Override
    public StrategyRunInfo getRunInfo(Long chatId, StrategyType type) {
        return orchestrator.getStatus(chatId, type);
    }

    // =====================================================================
    // ⭐ Создание дефолтных записей
    // =====================================================================
    private void createDefaultStrategies(Long chatId) {

        for (StrategyType type : StrategyType.values()) {

            StrategySettings s = StrategySettings.builder()
                    .chatId(chatId)
                    .type(type)
                    .symbol("BTCUSDT")
                    .timeframe("1m")
                    .cachedCandlesLimit(500)
                    .capitalUsd(BigDecimal.valueOf(100))
                    .commissionPct(BigDecimal.valueOf(0.05))
                    .takeProfitPct(BigDecimal.valueOf(1))
                    .stopLossPct(BigDecimal.valueOf(1))
                    .riskPerTradePct(BigDecimal.valueOf(1))
                    .dailyLossLimitPct(BigDecimal.valueOf(20))
                    .reinvestProfit(false)
                    .leverage(1)
                    .totalProfitPct(BigDecimal.ZERO)
                    .mlConfidence(BigDecimal.ZERO)
                    .exchangeName("BINANCE")
                    .networkType(NetworkType.TESTNET)
                    .active(false)
                    .build();

            settingsRepo.save(s);
        }

        log.info("✔ Созданы дефолтные StrategySettings для chatId={}", chatId);
    }

    private String getTitle(StrategyType type) {
        return switch (type) {
            case SMART_FUSION -> "Smart Fusion AI";
            case SCALPING -> "Scalping";
            case FIBONACCI_GRID -> "Fibonacci Grid";
            case RSI_EMA -> "RSI + EMA";
            default -> type.name();
        };
    }

    private String getDescription(StrategyType type) {
        return switch (type) {
            case SMART_FUSION -> "AI стратегия Multi-Filter + ML + ATR";
            case SCALPING -> "Скальпинг 30-300 сек";
            case FIBONACCI_GRID -> "Сетка уровней Фибоначчи";
            case RSI_EMA -> "Индикаторы RSI/EMA";
            default -> "Стратегия " + type.name();
        };
    }
}
