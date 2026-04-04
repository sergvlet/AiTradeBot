package com.chicu.aitradebot.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;
import java.util.Map;

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

    @Builder.Default
    private Map<String, Object> info = Map.of();

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

        @Builder.Default
        private TpSl tpSl = null;

        @Builder.Default
        private StrategyChartDto.WindowZone windowZone = null;

        @Builder.Default
        private List<PriceLine> priceLines = List.of();

        @Builder.Default
        private List<TradeMarker> trades = List.of();

        public static Layers empty() {
            return Layers.builder()
                    .levels(List.of())
                    .zone(null)
                    .tpSl(null)
                    .windowZone(null)
                    .priceLines(List.of())
                    .trades(List.of())
                    .build();
        }

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class WindowZone {
            private double high;
            private double low;

            public StrategyChartDto.WindowZone toOuter() {
                return StrategyChartDto.WindowZone.builder()
                        .high(high)
                        .low(low)
                        .build();
            }
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
    public static class TpSl {
        private Double tp;
        private Double sl;
    }

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
    public static class PriceLine {
        private String name;
        private Double price;
        private String color;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TradeMarker {
        private String side;
        private Double price;
        private Double qty;
        private Long time;
        private String source;
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
