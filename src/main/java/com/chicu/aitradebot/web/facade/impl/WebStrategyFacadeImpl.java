package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.UserProfile;
import com.chicu.aitradebot.domain.UserStrategy;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.repository.StrategySettingsRepository;
import com.chicu.aitradebot.repository.UserProfileRepository;
import com.chicu.aitradebot.repository.UserStrategyRepository;
import com.chicu.aitradebot.web.facade.WebStrategyFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebStrategyFacadeImpl implements WebStrategyFacade {

    private final UserProfileRepository userProfileRepository;
    private final StrategySettingsRepository strategySettingsRepository;
    private final UserStrategyRepository userStrategyRepository;

    private final AiStrategyOrchestrator aiStrategyOrchestrator;

    @Override
    public List<StrategyUi> getStrategies(Long chatId) {

        UserProfile user = userProfileRepository.findById(chatId)
                .orElseThrow(() -> new IllegalArgumentException("UserProfile not found: " + chatId));

        NetworkType networkType = user.getNetworkType(); // ✅ требуется шаблоном

        List<StrategySettings> allSettings = strategySettingsRepository.findAll();
        if (allSettings.isEmpty()) {
            log.warn("⚠ WebStrategyFacade: strategy_settings пустая, для chatId={} стратегий нет", chatId);
            return List.of();
        }

        List<UserStrategy> userStrategies = userStrategyRepository.findByUser(user);

        return allSettings.stream()
                .map(settings -> {
                    StrategyType type = settings.getType();

                    UserStrategy us = userStrategies.stream()
                            .filter(u ->
                                    u.getStrategySettings() != null &&
                                    u.getStrategySettings().getType() == type
                            )
                            .findFirst()
                            .orElse(null);

                    boolean active = us != null && us.isActive();

                    BigDecimal totalProfitPct = (us != null && us.getTotalProfitPct() != null)
                            ? us.getTotalProfitPct()
                            : BigDecimal.ZERO;

                    BigDecimal mlConfidence = (us != null && us.getMlConfidence() != null)
                            ? us.getMlConfidence()
                            : BigDecimal.ZERO;

                    String symbol = settings.getSymbol();
                    if (symbol == null || symbol.isBlank()) {
                        symbol = "BTCUSDT";
                    }

                    String title = resolveTitle(type);
                    String description = resolveDescription(type);

                    return new StrategyUi(
                            type,                                // StrategyType
                            active,                              // boolean active
                            title,                               // UI title
                            description,                         // UI description
                            chatId,                              // Long chatId
                            symbol,                              // String symbol
                            totalProfitPct.doubleValue(),        // double totalProfitPct
                            mlConfidence.doubleValue(),          // double mlConfidence
                            networkType                          // ✅ NetworkType
                    );
                })
                .toList();
    }

    @Override
    public void start(Long chatId, StrategyType strategyType) {
        log.info("▶ WebStrategyFacade.start chatId={}, type={}", chatId, strategyType);
        aiStrategyOrchestrator.startStrategy(chatId, strategyType);
    }

    @Override
    public void stop(Long chatId, StrategyType strategyType) {
        log.info("⏹ WebStrategyFacade.stop chatId={}, type={}", chatId, strategyType);
        aiStrategyOrchestrator.stopStrategy(chatId, strategyType);
    }

    @Override
    public void toggle(Long chatId, StrategyType strategyType) {
        log.info("🔁 WebStrategyFacade.toggle chatId={}, type={}", chatId, strategyType);

        UserProfile user = userProfileRepository.findById(chatId)
                .orElseThrow(() -> new IllegalArgumentException("UserProfile not found: " + chatId));

        List<UserStrategy> userStrategies = userStrategyRepository.findByUser(user);

        UserStrategy us = userStrategies.stream()
                .filter(u ->
                        u.getStrategySettings() != null &&
                        u.getStrategySettings().getType() == strategyType
                )
                .findFirst()
                .orElse(null);

        boolean currentlyActive = us != null && us.isActive();

        if (currentlyActive) {
            stop(chatId, strategyType);
        } else {
            start(chatId, strategyType);
        }
    }

    private String resolveTitle(StrategyType type) {
        if (type == null) return "UNKNOWN";
        return switch (type) {
            case SMART_FUSION -> "Smart Fusion AI";
            case FIBONACCI_GRID -> "Fibonacci Grid";
            case SCALPING -> "Scalping Pro";
            case ML_INVEST -> "ML Invest";
            default -> type.name();
        };
    }

    private String resolveDescription(StrategyType type) {
        if (type == null) return "Автоматическая торговая стратегия.";
        return switch (type) {
            case SMART_FUSION ->
                    "Мультиуровневая AI-стратегия с фильтрацией шума и RL-оркестратором.";
            case FIBONACCI_GRID ->
                    "Сеточная стратегия по уровням Фибоначчи с адаптивным TP/SL.";
            case SCALPING ->
                    "Быстрые сделки по импульсу цены и объёмам.";
            case ML_INVEST ->
                    "Среднесрочные позиции по сигналам ML-модели.";
            default -> "Автоматическая торговая стратегия.";
        };
    }
}
