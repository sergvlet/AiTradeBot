package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.repository.StrategySettingsRepository;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.service.UserProfileService;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.web.facade.WebStrategyFacade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebStrategyFacadeImpl implements WebStrategyFacade {

    private final StrategySettingsRepository settingsRepo;
    private final StrategySettingsService settingsService;
    private final UserProfileService userProfileService;
    private final AiStrategyOrchestrator orchestrator;

    // =====================================================================
    // 📌 Получение списка стратегий для UI
    // =====================================================================

    @Override
    public List<StrategyUi> getStrategies(Long chatId) {

        List<StrategySettings> list = settingsRepo.findByChatId(chatId);

        if (list.isEmpty()) {
            log.warn("⚠ strategy_settings пустая → создаём дефолтные, chatId={}", chatId);
            createDefaultStrategies(chatId);
            list = settingsRepo.findByChatId(chatId);
        }

        NetworkType network =
                userProfileService.findByChatId(chatId) != null
                        ? userProfileService.findByChatId(chatId).getNetworkType()
                        : NetworkType.MAINNET;

        List<StrategyUi> result = new ArrayList<>();

        for (StrategySettings s : list) {

            double profit = s.getTotalProfitPct() == null ? 0 : s.getTotalProfitPct().doubleValue();
            double conf   = s.getMlConfidence()     == null ? 0 : s.getMlConfidence().doubleValue();

            result.add(new StrategyUi(
                    s.getType(),
                    s.isActive(),
                    getTitle(s.getType()),
                    getDescription(s.getType()),
                    s.getChatId(),
                    s.getSymbol(),
                    profit,
                    conf,
                    network
            ));
        }

        return result;
    }

    // =====================================================================
    // ▶️ СТАРТ / СТОП
    // =====================================================================

    @Override
    public void start(Long chatId, StrategyType type) {
        orchestrator.startStrategy(chatId, type);
    }

    @Override
    public void stop(Long chatId, StrategyType type) {
        orchestrator.stopStrategy(chatId, type);
    }

    @Override
    public void toggle(Long chatId, StrategyType type) {

        StrategySettings s = settingsService.getSettings(chatId, type);

        if (s == null) {
            s = settingsService.getOrCreate(chatId, type);
        }

        if (s.isActive()) {
            orchestrator.stopStrategy(chatId, type);
            s.setActive(false);
        } else {
            orchestrator.startStrategy(chatId, type);
            s.setActive(true);
        }

        settingsService.save(s);
    }

    // =====================================================================
    // 🎯 СОЗДАНИЕ ДЕФОЛТНЫХ ЗАПИСЕЙ
    // =====================================================================

    private void createDefaultStrategies(Long chatId) {

        for (StrategyType type : StrategyType.values()) {

            StrategySettings s = StrategySettings.builder()
                    .chatId(chatId)
                    .type(type)
                    .symbol("BTCUSDT")
                    .timeframe("1h")
                    .cachedCandlesLimit(500)
                    .capitalUsd(BigDecimal.valueOf(100))
                    .commissionPct(BigDecimal.valueOf(0.05))
                    .takeProfitPct(BigDecimal.valueOf(1))
                    .stopLossPct(BigDecimal.valueOf(1))
                    .riskPerTradePct(BigDecimal.valueOf(1))
                    .dailyLossLimitPct(BigDecimal.valueOf(20))
                    .reinvestProfit(false)
                    .leverage(1)
                    .active(false)
                    .totalProfitPct(BigDecimal.ZERO)
                    .mlConfidence(BigDecimal.ZERO)
                    .build();

            settingsRepo.save(s);
        }

        log.info("✔ Созданы дефолтные StrategySettings для chatId={}", chatId);
    }

    // =====================================================================
    // 📝 UI labels
    // =====================================================================

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
