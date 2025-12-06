package com.chicu.aitradebot.web.controller.api;

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
     * FULL Стратегический график:
     *  — свечи
     *  — EMA, Bollinger
     *  — сделки
     *  — TP/SL уровни
     */
    @GetMapping("/strategy")
    public StrategyChartDto getStrategyChart(
            @RequestParam long chatId,
            @RequestParam String type,
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1m") String timeframe,
            @RequestParam(defaultValue = "500") int limit
    ) {
        log.info("📈 StrategyChart → chatId={} type={} symbol={} tf={} limit={}",
                chatId, type, symbol, timeframe, limit);

        // 🔥 ПЕРЕДАЁМ symbol в фасад (главный фикс!)
        return chartFacade.buildChart(chatId, type, symbol, timeframe, limit);
    }
}
