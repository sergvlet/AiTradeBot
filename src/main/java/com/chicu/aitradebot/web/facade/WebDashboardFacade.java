package com.chicu.aitradebot.web.facade;

import java.math.BigDecimal;

/**
 * WebDashboardFacade — фасад для UI дашборда.
 * Должен отдавать:
 *  - состояние стратегий
 *  - текущие цены
 *  - прогресс моделей
 *  - баланс
 *  - любую сводку
 */
public interface WebDashboardFacade {

    DashboardInfo getDashboard(Long chatId);

    // =============================================================
    // DTO
    // =============================================================
    record DashboardInfo(
            BigDecimal totalBalance,
            BigDecimal totalProfitPct,
            int activeStrategies,
            String lastUpdate
    ) {}
}
