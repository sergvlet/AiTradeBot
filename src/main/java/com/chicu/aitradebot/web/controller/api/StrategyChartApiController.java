package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.web.dto.StrategyChartDto;
import com.chicu.aitradebot.web.facade.WebChartFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chart")
public class StrategyChartApiController {

    private final WebChartFacade chartFacade;

    /**
     * 📈 SNAPSHOT стратегического графика

     * ❗ Единственный источник данных для:
     *  - UI графика
     *  - replay
     *  - backtest визуализации

     * ❌ НЕ:
     *  - стратегия
     *  - ордера
     *  - биржа
     */
    @GetMapping("/strategy")
    public StrategyChartDto getStrategyChart(
            @RequestParam long chatId,
            @RequestParam StrategyType type,
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1m") String timeframe,
            @RequestParam(defaultValue = "500") int limit
    ) {

        // ============================
        // VALIDATION
        // ============================

        if (chatId <= 0) {
            throw new IllegalArgumentException("chatId must be positive");
        }

        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must be provided");
        }

        if (limit < 10 || limit > 1500) {
            throw new IllegalArgumentException("limit must be between 10 and 1500");
        }

        String tf = timeframe.trim().toLowerCase();
        String sym = symbol.trim().toUpperCase();

        log.info(
                "📊 Chart SNAPSHOT → chatId={} type={} symbol={} tf={} limit={}",
                chatId, type, sym, tf, limit
        );

        // ============================
        // DELEGATE (ЕДИНАЯ ТОЧКА)
        // ============================

        return chartFacade.buildChart(
                chatId,
                type,
                sym,
                tf,
                limit
        );
    }
}
