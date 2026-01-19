package com.chicu.aitradebot.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyChartDto {

    @Builder.Default
    private List<CandleDto> candles = List.of();

    private Double lastPrice;

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
        private List<Double> levels = List.of();

        @Builder.Default
        private Zone zone = null;

        // ✅ ДОБАВЛЕНО: SCALPING window zone (high/low)
        @Builder.Default
        private WindowZone windowZone = null;

        public static Layers empty() {
            return Layers.builder()
                    .levels(List.of())
                    .zone(null)
                    .windowZone(null)
                    .build();
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

    // ✅ ДОБАВЛЕНО
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WindowZone {
        private double high;
        private double low;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandleDto {

        @JsonProperty("time")
        private long time;

        private double open;
        private double high;
        private double low;
        private double close;

        @JsonIgnore
        public static long toSeconds(long epochMillisOrSeconds) {
            return epochMillisOrSeconds > 3_000_000_000L
                    ? (epochMillisOrSeconds / 1000L)
                    : epochMillisOrSeconds;
        }

        public static CandleDto ofMillis(long epochMillis, double open, double high, double low, double close) {
            return CandleDto.builder()
                    .time(epochMillis / 1000L)
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .build();
        }

        public static CandleDto ofSeconds(long epochSeconds, double open, double high, double low, double close) {
            return CandleDto.builder()
                    .time(epochSeconds)
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .build();
        }
    }
}
