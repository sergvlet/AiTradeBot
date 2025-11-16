package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.web.controller.web.dto.StrategyChartDto;
import com.chicu.aitradebot.web.service.StrategyDashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 📈 Универсальный контроллер графика стратегий
 * (SmartFusion, RSI_EMA, Scalping, Fibonacci и т.д.)
 */
@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
@Slf4j
public class StrategyChartApiController {

    private final StrategyDashboardService dashboardService;

    /**
     * Возвращает данные для дашборда выбранной стратегии
     *
     * Пример вызова:
     * /api/strategy/chart?chatId=1&type=SMART_FUSION&limit=500&tf=15m
     */
    @GetMapping("/chart")
    public StrategyChartDto getChart(
            @RequestParam long chatId,
            @RequestParam String type,
            @RequestParam(defaultValue = "300") int limit,
            @RequestParam(required = false) String tf
    ) {
        log.info("📊 [DASHBOARD] Запрос графика стратегии [{}] chatId={} limit={} tf={}",
                type, chatId, limit, tf);

        return dashboardService.build(chatId, type, limit, tf);
    }
}
