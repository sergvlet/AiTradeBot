package com.chicu.aitradebot.web.service;

import com.chicu.aitradebot.web.facade.WebDashboardFacade;
import com.chicu.aitradebot.web.facade.WebStrategyFacade;
import com.chicu.aitradebot.web.facade.StrategyUi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * StrategyDashboardService (v4)
 *
 * Лёгкая прослойка между web-контроллерами и фасадами.
 * Никаких StrategyRegistry, никаких прямых стратегий.
 */
@Service
@RequiredArgsConstructor
public class StrategyDashboardService {

    private final WebDashboardFacade dashboardFacade;
    private final WebStrategyFacade strategyFacade;

    /**
     * Общая сводка дашборда (баланс, активные стратегии, pnl).
     */
    public WebDashboardFacade.DashboardInfo getDashboard(Long chatId) {
        return dashboardFacade.getDashboard(chatId);
    }

    /**
     * Список стратегий для UI.
     */
    public List<StrategyUi> getStrategies(Long chatId) {
        return strategyFacade.getStrategies(chatId);
    }
}
