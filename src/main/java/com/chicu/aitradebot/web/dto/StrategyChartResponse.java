package com.chicu.aitradebot.web.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyChartResponse {

    // =====================
    // 📈 MARKET
    // =====================
    private List<CandleDto> candles;
    private Double lastPrice;

    // =====================
    // 🧠 STRATEGY LAYERS
    // =====================
    private Layers layers;

    // =====================
    // DTOs
    // =====================

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Layers {
        private List<Double> levels;
        private Zone zone;
        private List<TradeMarker> trades;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Zone {
        private double top;
        private double bottom;
        private String color;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TradeMarker {
        private long time;      // seconds
        private String side;    // BUY / SELL
        private double price;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandleDto {
        private long time; // seconds
        private double open;
        private double high;
        private double low;
        private double close;
    }
}
