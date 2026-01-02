package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.web.facade.StrategyUi;
import com.chicu.aitradebot.web.facade.WebStrategyFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebStrategyFacadeImpl implements WebStrategyFacade {

    private final AiStrategyOrchestrator orchestrator;
    private final StrategySettingsService settingsService;

    // ================================================================
    // 📋 LIST — /strategies
    // ✅ ВСЕ StrategyType + настройки (если есть)
    // ================================================================
    @Override
    public List<StrategyUi> getStrategies(
            Long chatId,
            String exchange,
            NetworkType network
    ) {

        log.info(
                "📋 getStrategies chatId={} exchange={} network={}",
                chatId, exchange, network
        );

        // 1️⃣ Все настройки пользователя
        List<StrategySettings> settingsList =
                settingsService.findAllByChatId(chatId, exchange, network);

        // 2️⃣ Мапа type → settings
        Map<StrategyType, StrategySettings> settingsByType =
                settingsList.stream()
                        .collect(Collectors.toMap(
                                StrategySettings::getType,
                                s -> s,
                                (a, b) -> a   // защита от дублей
                        ));

        // 3️⃣ Строим UI по ВСЕМ стратегиям
        List<StrategyUi> result = new ArrayList<>();

        for (StrategyType type : StrategyType.values()) {

            StrategySettings settings = settingsByType.get(type);

            if (settings != null) {
                // ✅ корректно: через публичный fromSettings()
                StrategyUi ui =
                        StrategyUi.fromSettings(List.of(settings))
                                .stream()
                                .findFirst()
                                .orElseThrow();

                result.add(ui);

            } else {
                // 🧩 нет записи в БД — UI-заглушка
                result.add(
                        StrategyUi.empty(
                                chatId,
                                type,
                                exchange,
                                network
                        )
                );
            }
        }

        return result;
    }

    // ================================================================
    // 🔁 TOGGLE
    // ================================================================
    @Override
    public StrategyRunInfo toggle(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {

        StrategySettings settings =
                settingsService
                        .findLatest(chatId, type, exchange, network)
                        .orElseGet(() ->
                                settingsService.getOrCreate(chatId, type, exchange, network)
                        );

        StrategyRunInfo runtime =
                orchestrator.getStatus(
                        chatId,
                        type,
                        settings.getExchangeName(),
                        settings.getNetworkType()
                );

        boolean isRunning = runtime != null && runtime.isActive();

        log.info(
                "🔁 TOGGLE chatId={} type={} running={} symbol={} tf={}",
                chatId,
                type,
                isRunning,
                settings.getSymbol(),
                settings.getTimeframe()
        );

        return isRunning
                ? orchestrator.stopStrategy(
                chatId,
                type,
                settings.getExchangeName(),
                settings.getNetworkType()
        )
                : orchestrator.startStrategy(
                chatId,
                type,
                settings.getExchangeName(),
                settings.getNetworkType()
        );
    }

    // ================================================================
    // ℹ STATUS — ДАШБОРД
    // ================================================================
    @Override
    public StrategyRunInfo getRunInfo(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {

        StrategySettings settings =
                settingsService
                        .findLatest(chatId, type, exchange, network)
                        .orElse(null);

        if (settings == null) {
            return null;
        }

        StrategyRunInfo runtime =
                orchestrator.getStatus(
                        chatId,
                        type,
                        settings.getExchangeName(),
                        settings.getNetworkType()
                );

        if (runtime == null) {
            return null;
        }

        runtime.setSymbol(settings.getSymbol());
        runtime.setTimeframe(settings.getTimeframe());
        runtime.setTakeProfitPct(settings.getTakeProfitPct());
        runtime.setStopLossPct(settings.getStopLossPct());
        runtime.setCommissionPct(settings.getCommissionPct());
        runtime.setRiskPerTradePct(settings.getRiskPerTradePct());

        return runtime;
    }


}
