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
     * FULL стратегический график (SNAPSHOT):
     *  — свечи (market)
     *  — индикаторы
     *  — сделки
     *  — 🔥 СЛОИ СТРАТЕГИИ (levels / zone)
     */
    @GetMapping("/strategy")
    public StrategyChartDto getStrategyChart(
            @RequestParam long chatId,
            @RequestParam StrategyType type,   // ✅ enum, а не String
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1m") String timeframe,
            @RequestParam(defaultValue = "500") int limit
    ) {
        log.info(
                "📈 StrategyChart → chatId={} type={} symbol={} tf={} limit={}",
                chatId, type, symbol, timeframe, limit
        );

        return chartFacade.buildChart(
                chatId,
                type,
                symbol,
                timeframe,
                limit
        );
    }
}
