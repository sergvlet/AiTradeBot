package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.repository.StrategySettingsRepository;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.service.UserProfileService;
import com.chicu.aitradebot.web.facade.WebStrategyFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebStrategyFacadeImpl implements WebStrategyFacade {

    private final StrategySettingsRepository settingsRepo;
    private final StrategySettingsService settingsService;
    private final UserProfileService userProfileService;
    private final ExchangeSettingsService exchangeSettingsService;
    private final AiStrategyOrchestrator orchestrator;

    // =====================================================================
    // 📋 Список стратегий для страницы /strategies
    // =====================================================================
    @Override
    public List<StrategyUi> getStrategies(Long chatId) {

        // 1) Берём настройки стратегий строго по chatId
        List<StrategySettings> list = settingsService.findAllByChatId(chatId);

        if (list.isEmpty()) {
            log.warn("⚠ strategy_settings пустая → создаём дефолтные, chatId={}", chatId);
            createDefaultStrategies(chatId);
            list = settingsService.findAllByChatId(chatId);
        }

        // 2) Определяем основную биржу/сеть пользователя
        NetworkType networkType = resolvePrimaryNetwork(chatId);

        List<StrategyUi> result = new ArrayList<>();

        for (StrategySettings s : list) {

            double profit = s.getTotalProfitPct() == null
                    ? 0.0
                    : s.getTotalProfitPct().doubleValue();

            double conf = s.getMlConfidence() == null
                    ? 0.0
                    : s.getMlConfidence().doubleValue();

            boolean active = orchestrator.isActive(chatId, s.getType());

            result.add(new StrategyUi(
                    s.getType(),
                    active,
                    getTitle(s.getType()),
                    getDescription(s.getType()),
                    chatId,
                    s.getSymbol(),
                    profit,
                    conf,
                    networkType
            ));
        }

        // 3) Активные стратегии наверх
        result.sort(Comparator.comparing(StrategyUi::active).reversed());

        return result;
    }

    /**
     * Определяем "главную" сеть пользователя по его биржевым подключениями.
     * 1) Если есть включённое подключение — его network.
     * 2) Иначе берём первое.
     * 3) Если вообще нет записей — MAINNET.
     */
    private NetworkType resolvePrimaryNetwork(Long chatId) {
        List<ExchangeSettings> exchanges = exchangeSettingsService.findAllByChatId(chatId);

        if (exchanges.isEmpty()) {
            return NetworkType.MAINNET;
        }

        Optional<ExchangeSettings> enabled = exchanges.stream()
                .filter(ExchangeSettings::isEnabled)
                .findFirst();

        return enabled
                .map(ExchangeSettings::getNetwork)
                .orElseGet(() -> exchanges.get(0).getNetwork());
    }

    // =====================================================================
    // ▶️ Запуск/остановка стратегий
    // =====================================================================

    @Override
    public void start(Long chatId, StrategyType strategyType) {
        log.info("▶️ Web → START strategy {} for chatId={}", strategyType, chatId);

        StrategySettings s = settingsService.getOrCreate(chatId, strategyType);
        s.setActive(true);
        settingsService.save(s);

        orchestrator.startStrategy(chatId, strategyType);
    }

    @Override
    public void stop(Long chatId, StrategyType strategyType) {
        log.info("⏹ Web → STOP strategy {} for chatId={}", strategyType, chatId);

        StrategySettings s = settingsService.getOrCreate(chatId, strategyType);
        s.setActive(false);
        settingsService.save(s);

        orchestrator.stopStrategy(chatId, strategyType);
    }

    @Override
    public void toggle(Long chatId, StrategyType strategyType) {

        StrategySettings s = settingsService.getOrCreate(chatId, strategyType);

        boolean nowActive = orchestrator.isActive(chatId, strategyType);

        if (nowActive) {
            log.info("⏸ TOGGLE: останавливаем {} для chatId={}", strategyType, chatId);
            orchestrator.stopStrategy(chatId, strategyType);
            s.setActive(false);
        } else {
            log.info("▶️ TOGGLE: запускаем {} для chatId={}", strategyType, chatId);
            orchestrator.startStrategy(chatId, strategyType);
            s.setActive(true);
        }

        settingsService.save(s);
    }

    // =====================================================================
    // ⚙ Создание дефолтных стратегий (одна запись на type для chatId)
    // =====================================================================

    private void createDefaultStrategies(Long chatId) {

        for (StrategyType type : StrategyType.values()) {

            // если вдруг уже есть — не дублируем
            StrategySettings existing = settingsService.getSettings(chatId, type);
            if (existing != null) {
                continue;
            }

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
                    .active(false)
                    .totalProfitPct(BigDecimal.ZERO)
                    .mlConfidence(BigDecimal.ZERO)
                    .build();

            settingsRepo.save(s);
        }

        log.info("✔ Созданы дефолтные StrategySettings для chatId={}", chatId);
    }

    // =====================================================================
    // Текстовые названия и описания для UI
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
            case SCALPING -> "Скальпинг 30–300 сек";
            case FIBONACCI_GRID -> "Сетка уровней Фибоначчи";
            case RSI_EMA -> "Индикаторы RSI/EMA";
            default -> "Стратегия " + type.name();
        };
    }
}
