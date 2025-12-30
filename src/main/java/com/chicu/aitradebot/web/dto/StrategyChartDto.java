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

    // =====================
    // 📈 MARKET
    // =====================
    @Builder.Default
    private List<CandleDto> candles = List.of();

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

        @Builder.Default
        private Zone zone = null;

        public static Layers empty() {
            return Layers.builder()
                    .levels(List.of())
                    .zone(null)
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

        /**
         * Любой CSS-совместимый цвет (например "#22c55e" или "rgba(34,197,94,0.2)")
         */
        private String color;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandleDto {

        /**
         * 🔒 ВАЖНО: time всегда в UNIX SECONDS (не millis).
         * Это контракт для Lightweight Charts.
         */
        @JsonProperty("time")
        private long time;

        private double open;
        private double high;
        private double low;
        private double close;

        // -----------------------------
        // Утилиты, чтобы не путать ms/sec
        // -----------------------------

        @JsonIgnore
        public static long toSeconds(long epochMillisOrSeconds) {
            // если случайно пришли millis — конвертируем
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
