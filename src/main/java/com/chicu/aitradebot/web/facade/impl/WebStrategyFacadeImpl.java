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

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebStrategyFacadeImpl implements WebStrategyFacade {

    private final AiStrategyOrchestrator orchestrator;
    private final StrategySettingsService settingsService;

    // ================================================================
    // 📋 LIST (ФИЛЬТР ПО БИРЖЕ / СЕТИ)
    // ================================================================
    @Override
    public List<StrategyUi> getStrategies(
            Long chatId,
            String exchange,
            NetworkType network
    ) {
        List<StrategySettings> settings =
                settingsService.findAllByChatId(chatId, exchange, network);

        return StrategyUi.fromSettings(settings);
    }

    // ================================================================
    // ▶️ START
    // ================================================================
    @Override
    public StrategyRunInfo start(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {
        return orchestrator.startStrategy(chatId, type);
    }

    // ================================================================
    // ⏹ STOP
    // ================================================================
    @Override
    public StrategyRunInfo stop(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {
        return orchestrator.stopStrategy(chatId, type);
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
        StrategySettings s =
                settingsService.getOrCreate(chatId, type, exchange, network);

        return s.isActive()
                ? orchestrator.stopStrategy(chatId, type)
                : orchestrator.startStrategy(chatId, type);
    }

    // ================================================================
    // 🔁 TOGGLE (advanced)
    // ================================================================
    @Override
    public StrategyRunInfo toggleStrategy(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network,
            String symbol,
            String timeframe
    ) {

        StrategySettings s =
                settingsService.getOrCreate(chatId, type, exchange, network);

        if (symbol != null && !symbol.isBlank()) {
            s.setSymbol(symbol);
        }
        if (timeframe != null && !timeframe.isBlank()) {
            s.setTimeframe(timeframe);
        }

        settingsService.save(s);

        return s.isActive()
                ? orchestrator.stopStrategy(chatId, type)
                : orchestrator.startStrategy(chatId, type);
    }

    // ================================================================
    // ℹ STATUS
    // ================================================================
    @Override
    public StrategyRunInfo getRunInfo(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {
        return orchestrator.getStatus(chatId, type);
    }
}
