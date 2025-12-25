package com.chicu.aitradebot.web.service;

import com.chicu.aitradebot.common.enums.NetworkType;
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
 * ❌ Никаких StrategyRegistry
 * ❌ Никаких TradingStrategy
 * ✅ Только фасады
 */
@Service
@RequiredArgsConstructor
public class StrategyDashboardService {

    private final WebDashboardFacade dashboardFacade;
    private final WebStrategyFacade strategyFacade;

    // =============================================================
    // 🌍 DEFAULT CONTEXT (пока)
    // =============================================================
    private static final String DEFAULT_EXCHANGE = "BINANCE";
    private static final NetworkType DEFAULT_NETWORK = NetworkType.MAINNET;

    /**
     * Общая сводка дашборда (баланс, активные стратегии, pnl).
     */
    public WebDashboardFacade.DashboardInfo getDashboard(Long chatId) {
        return dashboardFacade.getDashboard(chatId);
    }

    /**
     * Список стратегий для UI (v4).
     */
    public List<StrategyUi> getStrategies(Long chatId) {
        return strategyFacade.getStrategies(
                chatId,
                DEFAULT_EXCHANGE,
                DEFAULT_NETWORK
        );
    }
}
