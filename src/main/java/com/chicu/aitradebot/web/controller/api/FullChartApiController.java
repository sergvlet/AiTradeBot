package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettings;
import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionCandleService;
import com.chicu.aitradebot.web.controller.web.dto.StrategyChartDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chart")
public class FullChartApiController {

    private final SmartFusionCandleService candleService;
    private final OrderService orderService;

    @GetMapping("/full")
    public StrategyChartDto getFull(
            @RequestParam Long chatId,
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1m") String timeframe,
            @RequestParam(defaultValue = "300") Integer limit
    ) {
        log.info("🔥 FULL /api/chart/full chatId={} symbol={} tf={} limit={}",
                chatId, symbol, timeframe, limit);

        SmartFusionStrategySettings cfg =
                candleService.buildSettings(chatId, symbol, timeframe, limit);

        // ❗ ТУТ БЫЛА ОШИБКА
        List<CandleProvider.Candle> raw = candleService.getCandles(cfg);

        var ema20 = candleService.calculateEma(raw, 20);
        var ema50 = candleService.calculateEma(raw, 50);

        var bb = calculateBollinger(raw, 20, 2.0);
        var atr = calculateATR(raw, 14);
        var supertrend = calculateSupertrend(raw, atr);

        List<OrderEntity> orders =
                orderService.getOrderEntitiesByChatIdAndSymbol(chatId, symbol);

        List<StrategyChartDto.TradeMarker> trades = orders.stream()
                .map(o -> StrategyChartDto.TradeMarker.builder()
                        .time(resolveTime(o))
                        .price(o.getPrice() != null ? o.getPrice().doubleValue() : 0.0)
                        .qty(o.getQuantity() != null ? o.getQuantity().doubleValue() : 0.0)
                        .side(o.getSide())
                        .build())
                .toList();

        Map<String, Double> stats = Map.of(
                "winRate", 0.0,
                "roi", 0.0
        );

        List<StrategyChartDto.CandleDto> candles = raw.stream()
                .map(c -> StrategyChartDto.CandleDto.builder()
                        .time(c.getTime())
                        .open(c.open())
                        .high(c.high())
                        .low(c.low())
                        .close(c.close())
                        .volume(1.0)
                        .build())
                .toList();

        return StrategyChartDto.builder()
                .symbol(symbol)
                .timeframe(timeframe)
                .candles(candles)
                .ema20(toPoints(ema20))
                .ema50(toPoints(ema50))
                .bollinger(bb)
                .atr(toPoints(atr))
                .supertrend(toPoints(supertrend))
                .trades(trades)
                .tpLevels(orders.stream()
                        .filter(o -> o.getTakeProfitPrice() != null)
                        .map(o -> o.getTakeProfitPrice().doubleValue())
                        .toList())
                .slLevels(orders.stream()
                        .filter(o -> o.getStopLossPrice() != null)
                        .map(o -> o.getStopLossPrice().doubleValue())
                        .toList())
                .stats(stats)
                .lastPrice(candleService.getLastPrice(symbol))
                .serverTime(System.currentTimeMillis())
                .build();
    }

    // ========== helpers ==========

    private long resolveTime(OrderEntity o) {
        if (o.getTimestamp() != null) return o.getTimestamp();
        if (o.getCreatedAt() != null)
            return o.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return System.currentTimeMillis();
    }

    private List<StrategyChartDto.LinePoint> toPoints(List<Map<String, Object>> src) {
        return src.stream()
                .map(m -> StrategyChartDto.LinePoint.builder()
                        .time(((Number) m.get("time")).longValue())
                        .value(((Number) m.get("value")).doubleValue())
                        .build())
                .toList();
    }

    private StrategyChartDto.LinePoint pt(long t, double v) {
        return StrategyChartDto.LinePoint.builder().time(t).value(v).build();
    }

    // ========== INDICATORS ==========

    private Map<String, List<StrategyChartDto.LinePoint>> calculateBollinger(
            List<CandleProvider.Candle> candles,
            int period,
            double k
    ) {
        List<StrategyChartDto.LinePoint> upper = new ArrayList<>();
        List<StrategyChartDto.LinePoint> lower = new ArrayList<>();
        List<StrategyChartDto.LinePoint> middle = new ArrayList<>();

        for (int i = 0; i < candles.size(); i++) {
            if (i < period) {
                long t = candles.get(i).getTime();
                upper.add(pt(t, 0));
                lower.add(pt(t, 0));
                middle.add(pt(t, 0));
                continue;
            }

            List<Double> slice = candles.subList(i - period, i).stream()
                    .map(CandleProvider.Candle::close)
                    .toList();

            double mean = slice.stream().mapToDouble(d -> d).average().orElse(0);
            double std = Math.sqrt(slice.stream()
                                           .mapToDouble(v -> Math.pow(v - mean, 2))
                                           .sum() / period);

            long t = candles.get(i).getTime();

            middle.add(pt(t, mean));
            upper.add(pt(t, mean + std * k));
            lower.add(pt(t, mean - std * k));
        }

        return Map.of(
                "upper", upper,
                "middle", middle,
                "lower", lower
        );
    }

    private List<Map<String, Object>> calculateATR(
            List<CandleProvider.Candle> candles,
            int period
    ) {
        List<Map<String, Object>> arr = new ArrayList<>();
        double atr = 0.0;

        for (int i = 1; i < candles.size(); i++) {
            CandleProvider.Candle c = candles.get(i);
            CandleProvider.Candle p = candles.get(i - 1);

            double tr = Math.max(
                    Math.max(c.high() - c.low(), Math.abs(c.high() - p.close())),
                    Math.abs(c.low() - p.close())
            );

            atr = (i < period)
                    ? ((atr * (i - 1)) + tr) / i
                    : (atr * (period - 1) + tr) / period;

            arr.add(Map.of(
                    "time", c.getTime(),
                    "value", atr
            ));
        }

        return arr;
    }

    private List<Map<String, Object>> calculateSupertrend(
            List<CandleProvider.Candle> candles,
            List<Map<String, Object>> atr
    ) {
        return atr.stream()
                .map(m -> Map.of(
                        "time", m.get("time"),
                        "value", ((Number) m.get("value")).doubleValue()
                ))
                .toList();
    }
}
