package com.chicu.aitradebot.web.facade.impl;

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
    // 📋 LIST
    // ================================================================
    @Override
    public List<StrategyUi> getStrategies(Long chatId) {

        List<StrategySettings> settings =
                settingsService.findAllByChatId(chatId);

        return StrategyUi.fromSettings(settings);
    }

    // ================================================================
    // ▶️ START
    // ================================================================
    @Override
    public void start(Long chatId, StrategyType type) {
        orchestrator.startStrategy(chatId, type);
    }

    // ================================================================
    // ⏹ STOP
    // ================================================================
    @Override
    public void stop(Long chatId, StrategyType type) {
        orchestrator.stopStrategy(chatId, type);
    }

    // ================================================================
    // 🔁 TOGGLE
    // ================================================================
    @Override
    public void toggle(Long chatId, StrategyType type) {
        StrategySettings s = settingsService.getOrCreate(chatId, type);

        if (s.isActive()) {
            stop(chatId, type);
        } else {
            start(chatId, type);
        }
    }

    // ================================================================
    // 🔁 TOGGLE (advanced)
    // ================================================================
    @Override
    public StrategyRunInfo toggleStrategy(Long chatId,
                                          StrategyType type,
                                          String symbol,
                                          String timeframe) {

        StrategySettings s = settingsService.getOrCreate(chatId, type);

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
    public StrategyRunInfo getRunInfo(Long chatId, StrategyType type) {
        return orchestrator.getStatus(chatId, type);
    }
}
