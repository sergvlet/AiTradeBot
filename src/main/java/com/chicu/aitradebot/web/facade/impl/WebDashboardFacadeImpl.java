package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.web.facade.WebDashboardFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Реализация WebDashboardFacade.
 *
 * Очень тонкий слой: просто собирает инфу с Orchestrator.
 * НЕ лезет в биржу, НЕ лезет в стратегии напрямую.
 */
@Service
@RequiredArgsConstructor
public class WebDashboardFacadeImpl implements WebDashboardFacade {

    private final AiStrategyOrchestrator orchestrator;

    @Override
    public DashboardInfo getDashboard(Long chatId) {

        var state = orchestrator.getGlobalState(chatId);

        return new DashboardInfo(
                state.totalBalance(),
                state.totalProfitPct(),
                state.activeStrategies(),
                Instant.now().toString()
        );
    }
}
