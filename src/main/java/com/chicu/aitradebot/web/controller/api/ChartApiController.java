package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionCandleService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chart")
public class ChartApiController {

    private final SmartFusionCandleService candleService;
    private final OrderService orderService;

    // =============================================================
    // DTO
    // =============================================================

    @Data
    @AllArgsConstructor
    static class CandleDto {
        long time;
        double open;
        double high;
        double low;
        double close;
    }

    @Data
    @AllArgsConstructor
    static class EmaPoint {
        long time;
        double value;
    }

    @Data
    @AllArgsConstructor
    static class TradeMarker {
        long id;

        long time;
        String side;
        double price;
        double qty;

        String status;
        String strategyType;

        Double exitPrice;
        Long exitTime;

        Double tpPrice;
        Double slPrice;

        Boolean tpHit;
        Boolean slHit;

        Double pnlUsd;
        Double pnlPct;

        String entryReason;
        String exitReason;
        Double mlConfidence;
    }

    @Data
    @AllArgsConstructor
    static class ChartResponse {
        List<CandleDto> candles;
        List<EmaPoint> emaFast;
        List<EmaPoint> emaSlow;
        List<TradeMarker> trades;
    }

    // =============================================================
    // API: /api/chart/history
    // =============================================================

    @GetMapping("/history")
    public ChartResponse getChart(
            @RequestParam long chatId,
            @RequestParam String symbol,
            @RequestParam(required = false) String timeframe,
            @RequestParam(defaultValue = "250") int limit
    ) {

        try {

            // ======================
            // 1) Нормализация timeframe
            // ======================
            if (timeframe == null || timeframe.isBlank()) {
                timeframe = "15m";
            }

            // ======================
            // 2) Свечи
            // ======================
            var candleSettings = candleService.buildSettings(chatId, symbol, timeframe, limit);
            var candles = candleService.getCandles(candleSettings);

            List<CandleDto> candleDto = new ArrayList<>();
            for (var c : candles) {
                candleDto.add(new CandleDto(
                        c.ts().toEpochMilli(),
                        c.open(),
                        c.high(),
                        c.low(),
                        c.close()
                ));
            }

            // ======================
            // 3) EMA fast / slow
            // ======================
            var emaFast = candleService.calculateEma(candles, 9).stream()
                    .map(e -> new EmaPoint(
                            ((Number) e.get("time")).longValue(),
                            ((Number) e.get("value")).doubleValue()
                    )).toList();

            var emaSlow = candleService.calculateEma(candles, 21).stream()
                    .map(e -> new EmaPoint(
                            ((Number) e.get("time")).longValue(),
                            ((Number) e.get("value")).doubleValue()
                    )).toList();

            // ======================
            // 4) Сделки
            // ======================
            List<OrderEntity> orders = orderService.getOrderEntitiesByChatIdAndSymbol(chatId, symbol);
            List<TradeMarker> trades = new ArrayList<>();

            for (OrderEntity o : orders) {

                long time = o.getTimestamp() != null ? o.getTimestamp() : System.currentTimeMillis();
                Long exitTime = o.getExitTimestamp();

                trades.add(new TradeMarker(
                        o.getId() != null ? o.getId() : -1L,
                        time,
                        o.getSide(),
                        o.getPrice() != null ? o.getPrice().doubleValue() : 0.0,
                        o.getQuantity() != null ? o.getQuantity().doubleValue() : 0.0,
                        o.getStatus(),
                        o.getStrategyType(),
                        o.getExitPrice() != null ? o.getExitPrice().doubleValue() : null,
                        exitTime,
                        o.getTakeProfitPrice() != null ? o.getTakeProfitPrice().doubleValue() : null,
                        o.getStopLossPrice() != null ? o.getStopLossPrice().doubleValue() : null,
                        o.getTpHit(),
                        o.getSlHit(),
                        o.getRealizedPnlUsd() != null ? o.getRealizedPnlUsd().doubleValue() : null,
                        o.getRealizedPnlPct() != null ? o.getRealizedPnlPct().doubleValue() : null,
                        o.getEntryReason(),
                        o.getExitReason(),
                        o.getMlConfidence() != null ? o.getMlConfidence().doubleValue() : null
                ));
            }

            // ======================
            // 5) Лог
            // ======================
            log.info("📊 /api/chart/history chatId={} symbol={} timeframe={} candles={} trades={}",
                    chatId, symbol, timeframe, candleDto.size(), trades.size());

            return new ChartResponse(candleDto, emaFast, emaSlow, trades);

        } catch (Exception e) {
            log.error("❌ ChartApiController error: {}", e.getMessage(), e);

            return new ChartResponse(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList()
            );
        }
    }

}
