package com.chicu.aitradebot.web.controller.web.dto;

import lombok.*;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyChartDto {

    private String symbol;
    private String timeframe;
    private List<LinePoint> equity;
    private Map<String, Double> kpis;
    private List<CandleDto> candles;
    /** 📅 PnL по месяцам (например: {"2025-01": +12.4}) */
    private Map<String, Double> monthlyPnl;

    // EMA (универсальные)
    private List<LinePoint> emaFast;
    private List<LinePoint> emaSlow;

    // EMA (конкретные названия, чтобы твой контроллер работал)
    private List<LinePoint> ema20;
    private List<LinePoint> ema50;

    // Bollinger Bands: {"upper": [...], "middle": [...], "lower": [...]}
    private Map<String, List<LinePoint>> bollinger;

    // ATR points
    private List<LinePoint> atr;

    // Supertrend points
    private List<LinePoint> supertrend;

    // Orders/Trades
    private List<TradeMarker> trades;

    private List<Double> tpLevels;
    private List<Double> slLevels;

    // KPI / Stats
    private Map<String, Double> stats;

    // Meta info
    private double lastPrice;
    private long serverTime;

    // ======================== Inner DTOs ========================

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandleDto {
        private long time;
        private double open;
        private double high;
        private double low;
        private double close;
        private double volume;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinePoint {
        private long time;
        private double value;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TradeMarker {
        private long time;
        private double price;
        private double qty;
        private String side;
    }
}
