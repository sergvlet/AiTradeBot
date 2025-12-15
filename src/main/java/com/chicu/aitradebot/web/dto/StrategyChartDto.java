package com.chicu.aitradebot.web.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyChartDto {

    // =====================
    // 📈 MARKET
    // =====================
    private List<CandleDto> candles;
    private Double lastPrice;

    // =====================
    // 🧠 STRATEGY LAYERS (КЛЮЧЕВО)
    // =====================
    @Builder.Default
    private Layers layers = Layers.empty();

    // =====================================================
    // DTOs
    // =====================================================

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Layers {

        @Builder.Default
        private List<Double> levels = List.of(); // Fibonacci / Grid

        private Zone zone;

        public static Layers empty() {
            return Layers.builder().build();
        }
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
    public static class CandleDto {
        private long time; // seconds
        private double open;
        private double high;
        private double low;
        private double close;
    }
}
