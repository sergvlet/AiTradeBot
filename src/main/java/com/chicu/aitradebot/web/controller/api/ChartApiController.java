package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettings;
import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionCandleService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

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

    @Data @AllArgsConstructor
    static class CandleDto {
        long time;
        double open;
        double high;
        double low;
        double close;
    }

    @Data @AllArgsConstructor
    static class EmaPointDto {
        long time;
        double value;
    }

    @Data @AllArgsConstructor
    static class TradeDto {
        Long id;
        long time;
        String side;
        double price;
        double qty;
        String status;
        Double tpPrice;
        Double slPrice;
        Double pnlUsd;
        Double pnlPct;
        String strategyType;
        String entryReason;
        String exitReason;
        Double mlConfidence;
    }

    @Data @AllArgsConstructor
    static class ChartResponse {
        List<CandleDto> candles;
        List<EmaPointDto> emaFast;
        List<EmaPointDto> emaSlow;
        List<TradeDto> trades;
    }


    // =============================================================
    //     ✔ FIXED /api/chart/candles — FULL DATA FOR JS
    // =============================================================
    @GetMapping("/candles")
    public ChartResponse getCandles(
            @RequestParam(required = false) Long chatId,
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1m") String timeframe,
            @RequestParam(defaultValue = "200") Integer limit
    ) {
        long cid = chatId != null ? chatId : 0L;

        log.info("🔥 /api/chart/candles symbol={}, tf={}, chatId={}, limit={}",
                symbol, timeframe, cid, limit);

        // build settings for candle loader
        SmartFusionStrategySettings cfg =
                candleService.buildSettings(cid, symbol, timeframe, limit);

        // 1️⃣ Load candles
        var raw = candleService.getCandles(cfg);

        List<CandleDto> candles = raw.stream()
                .map(c -> new CandleDto(
                        c.getTime(),
                        c.open(),
                        c.high(),
                        c.low(),
                        c.close()
                ))
                .toList();

        // 2️⃣ EMA20 / EMA50
        var ema20raw = candleService.calculateEma(raw, 20);
        var ema50raw = candleService.calculateEma(raw, 50);

        List<EmaPointDto> emaFast = ema20raw.stream()
                .map(m -> new EmaPointDto(
                        ((Number)m.get("time")).longValue(),
                        ((Number)m.get("value")).doubleValue()
                ))
                .toList();

        List<EmaPointDto> emaSlow = ema50raw.stream()
                .map(m -> new EmaPointDto(
                        ((Number)m.get("time")).longValue(),
                        ((Number)m.get("value")).doubleValue()
                ))
                .toList();

        // 3️⃣ Trades (buy/sell markers)
        List<OrderEntity> entities =
                orderService.getOrderEntitiesByChatIdAndSymbol(cid, symbol);

        if (entities.size() > limit)
            entities = entities.subList(entities.size() - limit, entities.size());

        List<TradeDto> trades = entities.stream()
                .map(o -> new TradeDto(
                        o.getId(),
                        resolveOrderTime(o),
                        o.getSide(),
                        safe(o.getPrice()),
                        safe(o.getQuantity()),
                        o.getStatus(),
                        safeNull(o.getTakeProfitPrice()),
                        safeNull(o.getStopLossPrice()),
                        safeNull(o.getRealizedPnlUsd()),
                        safeNull(o.getRealizedPnlPct()),
                        o.getStrategyType(),
                        o.getEntryReason(),
                        o.getExitReason(),
                        safeNull(o.getMlConfidence())
                ))
                .toList();

        // 4️⃣ return FULL DATA (required by JS)
        return new ChartResponse(candles, emaFast, emaSlow, trades);
    }


    // =============================================================
    // /api/chart/history — unchanged
    // =============================================================
    @GetMapping("/history")
    public ChartResponse getHistory(
            @RequestParam Long chatId,
            @RequestParam String symbol,
            @RequestParam(defaultValue = "15m") String timeframe,
            @RequestParam(defaultValue = "250") Integer limit
    ) {
        log.info("📈 /api/chart/history chatId={}, symbol={}, timeframe={}, limit={}",
                chatId, symbol, timeframe, limit);

        SmartFusionStrategySettings cfg =
                candleService.buildSettings(chatId, symbol, timeframe, limit);

        var candlesRaw = candleService.getCandles(cfg);

        List<CandleDto> candles = candlesRaw.stream()
                .map(c -> new CandleDto(
                        c.getTime(), c.open(), c.high(), c.low(), c.close()
                ))
                .toList();

        var emaFastRaw = candleService.calculateEma(candlesRaw, 20);
        var emaSlowRaw = candleService.calculateEma(candlesRaw, 50);

        List<EmaPointDto> emaFast = emaFastRaw.stream()
                .map(m -> new EmaPointDto(
                        ((Number)m.get("time")).longValue(),
                        ((Number)m.get("value")).doubleValue()
                ))
                .toList();

        List<EmaPointDto> emaSlow = emaSlowRaw.stream()
                .map(m -> new EmaPointDto(
                        ((Number)m.get("time")).longValue(),
                        ((Number)m.get("value")).doubleValue()
                ))
                .toList();

        List<OrderEntity> entities =
                orderService.getOrderEntitiesByChatIdAndSymbol(chatId, symbol);

        if (entities.size() > limit)
            entities = entities.subList(entities.size() - limit, entities.size());

        List<TradeDto> trades = entities.stream()
                .map(o -> new TradeDto(
                        o.getId(),
                        resolveOrderTime(o),
                        o.getSide(),
                        safe(o.getPrice()),
                        safe(o.getQuantity()),
                        o.getStatus(),
                        safeNull(o.getTakeProfitPrice()),
                        safeNull(o.getStopLossPrice()),
                        safeNull(o.getRealizedPnlUsd()),
                        safeNull(o.getRealizedPnlPct()),
                        o.getStrategyType(),
                        o.getEntryReason(),
                        o.getExitReason(),
                        safeNull(o.getMlConfidence())
                ))
                .toList();

        return new ChartResponse(candles, emaFast, emaSlow, trades);
    }


    // =============================================================
    // Helpers
    // =============================================================

    private long resolveOrderTime(OrderEntity o) {
        try {
            if (o.getTimestamp() != null)
                return o.getTimestamp();
        } catch (Exception ignored) {}

        try {
            if (o.getCreatedAt() != null)
                return o.getCreatedAt().atZone(ZoneId.systemDefault())
                        .toInstant().toEpochMilli();
        } catch (Exception ignored) {}

        return System.currentTimeMillis();
    }

    private double safe(Number n) {
        return n != null ? n.doubleValue() : 0.0;
    }

    private Double safeNull(Number n) {
        return n != null ? n.doubleValue() : null;
    }
}
