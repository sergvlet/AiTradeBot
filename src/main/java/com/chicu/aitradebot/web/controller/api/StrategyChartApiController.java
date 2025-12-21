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
     * FULL стратегический график (SNAPSHOT)
     *  — свечи (market)
     *  — last price
     *  — UI layers (levels / zone / tp-sl и т.д.)
     * ❗️Без чтения БД стратегии
     */
    @GetMapping("/strategy")
    public StrategyChartDto getStrategyChart(
            @RequestParam long chatId,                 // ✅ ИСПРАВЛЕНО
            @RequestParam StrategyType type,
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1m") String timeframe,
            @RequestParam(defaultValue = "500") int limit
    ) {

        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Symbol must be provided");
        }

        if (limit < 10 || limit > 2000) {
            throw new IllegalArgumentException("Limit must be between 10 and 2000");
        }

        String tf = timeframe.toLowerCase();

        log.info(
                "📈 StrategyChart → chatId={} type={} symbol={} tf={} limit={}",
                chatId, type, symbol, tf, limit
        );

        return chartFacade.buildChart(
                chatId,
                type,
                symbol.toUpperCase(),
                tf,
                limit
        );
    }
}
