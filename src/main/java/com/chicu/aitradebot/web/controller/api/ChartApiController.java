package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chart")
public class ChartApiController {

    @Data @Builder
    public static class CandleDto {
        long time;
        double open;
        double high;
        double low;
        double close;
    }

    @Data @Builder
    public static class PointDto {
        long time;
        double value;
    }

    @Data @Builder
    public static class TradeDto {
        long time;
        double price;
        String side;
    }

    @Data @Builder
    public static class BollingerDto {
        List<PointDto> upper;
        List<PointDto> lower;
        List<PointDto> middle;
    }

    @Data @Builder
    public static class StrategyChartResponse {
        List<CandleDto> candles;
        List<PointDto> ema20;
        List<PointDto> ema50;
        BollingerDto bollinger;
        List<TradeDto> trades;
    }

    // ================================
    // ✔ API, которое вызывает frontend
    // ================================
    @GetMapping("/full")
    public StrategyChartResponse getFull(
            @RequestParam long chatId,
            @RequestParam String symbol,
            @RequestParam String timeframe,
            @RequestParam(defaultValue = "300") int limit
    ) {
        log.warn("⚠️ /api/chart/full — устаревший API, возвращаем пустые данные");
        return StrategyChartResponse.builder()
                .candles(Collections.emptyList())
                .ema20(Collections.emptyList())
                .ema50(Collections.emptyList())
                .bollinger(BollingerDto.builder()
                        .upper(Collections.emptyList())
                        .lower(Collections.emptyList())
                        .middle(Collections.emptyList())
                        .build())
                .trades(Collections.emptyList())
                .build();
    }


}
