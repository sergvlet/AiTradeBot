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
        long time;      // ms
        double open;
        double high;
        double low;
        double close;
    }

    @Data
    @AllArgsConstructor
    static class EmaPoint {
        long time;      // ms
        double value;
    }

    @Data
    @AllArgsConstructor
    static class TradeMarker {
        long id;

        long time;          // время входа (ms)
        String side;        // BUY / SELL
        double price;       // цена входа
        double qty;         // объём

        String status;      // NEW / FILLED / CANCELED ...
        String strategyType;

        // Выход и TP/SL
        Double exitPrice;   // цена выхода
        Long exitTime;      // время выхода (ms)

        Double tpPrice;
        Double slPrice;

        Boolean tpHit;
        Boolean slHit;

        // PnL
        Double pnlUsd;
        Double pnlPct;

        // Причины и ML
        String entryReason;
        String exitReason;
        Double mlConfidence;   // 0..1
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
    //  API: /api/chart/history?chatId=123&symbol=BTCUSDT&timeframe=15m&limit=250
    // =============================================================

    @GetMapping("/history")
    public ChartResponse getChart(
            @RequestParam long chatId,
            @RequestParam String symbol,
            @RequestParam(defaultValue = "15m") String timeframe,
            @RequestParam(defaultValue = "250") int limit
    ) {

        try {
            // ---------- 1) Свечи ----------
            var settings = candleService.buildSettings(chatId, symbol, timeframe, limit);
            var candles = candleService.getCandles(settings);

            List<CandleDto> candleDto = new ArrayList<>();
            for (var c : candles) {
                candleDto.add(
                        new CandleDto(
                                c.ts().toEpochMilli(),
                                c.open(),
                                c.high(),
                                c.low(),
                                c.close()
                        )
                );
            }

            // ---------- 2) EMA (быстрая/медленная) ----------
            var emaFast = candleService.calculateEma(candles, 9).stream()
                    .map(e -> new EmaPoint(
                            ((Number) e.get("time")).longValue(),
                            ((Number) e.get("value")).doubleValue()
                    ))
                    .toList();

            var emaSlow = candleService.calculateEma(candles, 21).stream()
                    .map(e -> new EmaPoint(
                            ((Number) e.get("time")).longValue(),
                            ((Number) e.get("value")).doubleValue()
                    ))
                    .toList();

            // ---------- 3) Сделки для маркеров ----------
            List<OrderEntity> orders = orderService.getOrderEntitiesByChatIdAndSymbol(chatId, symbol);
            List<TradeMarker> trades = new ArrayList<>();

            for (OrderEntity o : orders) {

                long time = (o.getTimestamp() != null)
                        ? o.getTimestamp()
                        : System.currentTimeMillis();

                Long exitTime = (o.getExitTimestamp() != null)
                        ? o.getExitTimestamp()
                        : null;

                double price = (o.getPrice() != null) ? o.getPrice().doubleValue() : 0.0;
                double qty = (o.getQuantity() != null) ? o.getQuantity().doubleValue() : 0.0;

                Double exitPrice = (o.getExitPrice() != null) ? o.getExitPrice().doubleValue() : null;
                Double tpPrice = (o.getTakeProfitPrice() != null) ? o.getTakeProfitPrice().doubleValue() : null;
                Double slPrice = (o.getStopLossPrice() != null) ? o.getStopLossPrice().doubleValue() : null;

                Double pnlUsd = (o.getRealizedPnlUsd() != null) ? o.getRealizedPnlUsd().doubleValue() : null;
                Double pnlPct = (o.getRealizedPnlPct() != null) ? o.getRealizedPnlPct().doubleValue() : null;

                Double mlConf = (o.getMlConfidence() != null) ? o.getMlConfidence().doubleValue() : null;

                TradeMarker marker = new TradeMarker(
                        o.getId() != null ? o.getId() : -1L,
                        time,
                        o.getSide(),
                        price,
                        qty,
                        o.getStatus(),
                        o.getStrategyType(),
                        exitPrice,
                        exitTime,
                        tpPrice,
                        slPrice,
                        o.getTpHit(),
                        o.getSlHit(),
                        pnlUsd,
                        pnlPct,
                        o.getEntryReason(),
                        o.getExitReason(),
                        mlConf
                );

                trades.add(marker);
            }

            log.info("📊 /api/chart/history chatId={}, symbol={}, tf={}, candles={}, trades={}",
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
