package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.web.dto.StrategyChartDto;
import com.chicu.aitradebot.web.facade.WebChartFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chart")
public class StrategyChartApiController {

    private final WebChartFacade chartFacade;

    @GetMapping("/strategy")
    public StrategyChartDto getStrategyChart(
            @RequestParam long chatId,
            @RequestParam StrategyType type,
            @RequestParam String symbol,
            @RequestParam(required = false) String timeframe,
            @RequestParam(required = false) Integer limit
    ) {
        if (chatId <= 0) throw new IllegalArgumentException("chatId must be positive");
        if (type == null) throw new IllegalArgumentException("type must be provided");
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol must be provided");

        final String sym = symbol.trim().toUpperCase(Locale.ROOT);

        // нормализуем "<default>" / "default" / пустоту -> null
        final String tf = normalizeOptional(timeframe);
        final int lim = normalizeLimit(limit);

        log.info("📊 Chart SNAPSHOT → chatId={} type={} symbol={} tf={} limit={}",
                chatId, type, sym,
                (tf == null ? "<from-settings>" : tf),
                (lim == 0 ? "<from-settings>" : lim)
        );

        return chartFacade.buildChart(chatId, type, sym, tf, lim);
    }

    private static String normalizeOptional(String v) {
        if (v == null) return null;
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) return null;
        if (s.equals("default") || s.equals("<default>")) return null;
        return s;
    }

    private static int normalizeLimit(Integer v) {
        if (v == null) return 0;          // 0 = возьми из настроек
        if (v < 10 || v > 1500) return 0;  // тоже отдадим на резолв в settings
        return v;
    }
}
