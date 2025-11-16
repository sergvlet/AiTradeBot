package com.chicu.aitradebot.web.controller.web.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data @Builder
public class StrategyChartDto {
    @Data @Builder
    public static class CandleDto {
        long time; double open, high, low, close, volume;
    }
    @Data @Builder
    public static class LinePoint { long time; double value; }
    @Data @Builder
    public static class TradeMarker { long time; String side; double price; double qty; }

    List<CandleDto> candles;
    List<LinePoint> emaFast;
    List<LinePoint> emaSlow;
    List<TradeMarker> trades;
    List<Double> tpLevels;
    List<Double> slLevels;

    List<LinePoint> equity;
    Map<String, Double> kpis;
    Map<String, Double> monthlyPnl;
}
