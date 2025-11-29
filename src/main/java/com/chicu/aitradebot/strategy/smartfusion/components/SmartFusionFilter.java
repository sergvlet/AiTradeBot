package com.chicu.aitradebot.strategy.smartfusion.components;

import com.chicu.aitradebot.strategy.core.CandleProvider;
import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettings;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Smart Filter v4.0 (EMA + RSI + ATR)
 * Уровень 1 — определение тренда, силы сигнала и волатильности.
 */
@Component
@Slf4j
public class SmartFusionFilter {

    /** Основная структура данных сигнала */
    @Data
    @Builder
    @AllArgsConstructor
    public static class FilterSignal {
        private boolean bullish;
        private boolean bearish;
        private double emaFast;
        private double emaSlow;
        private double rsi;
        private double atr;
        private double trendStrength;

        @Override
        public String toString() {
            return String.format("EMA9=%.4f EMA21=%.4f RSI=%.2f ATR=%.4f %s",
                    emaFast, emaSlow, rsi, atr,
                    bullish ? "↑UP" : bearish ? "↓DOWN" : "—HOLD");
        }
    }

    /**
     * Расчёт фильтра: EMA + RSI + ATR
     */
    public FilterSignal evaluate(List<CandleProvider.Candle> candles,
                                 SmartFusionStrategySettings cfg) {

        if (candles == null || candles.size() < 30) {
            return FilterSignal.builder()
                    .bullish(false).bearish(false)
                    .rsi(50).emaFast(0).emaSlow(0).atr(0)
                    .trendStrength(0).build();
        }

        // ✅ Переход на CandleProvider.Candle
        List<Double> closes = candles.stream()
                .map(CandleProvider.Candle::close)
                .collect(Collectors.toList());
        List<Double> highs  = candles.stream()
                .map(CandleProvider.Candle::high)
                .collect(Collectors.toList());
        List<Double> lows   = candles.stream()
                .map(CandleProvider.Candle::low)
                .collect(Collectors.toList());

        double emaFast = ema(closes, cfg.getEmaFastPeriod());
        double emaSlow = ema(closes, cfg.getEmaSlowPeriod());
        double rsi = rsi(closes, cfg.getRsiPeriod());
        double atr = atr(highs, lows, closes, cfg.getAtrPeriod());

        boolean bullish = emaFast > emaSlow && rsi < cfg.getRsiBuyThreshold();
        boolean bearish = emaFast < emaSlow && rsi > cfg.getRsiSellThreshold();

        double trendStrength = Math.abs((emaFast / emaSlow) - 1.0) * 100;

        return FilterSignal.builder()
                .bullish(bullish)
                .bearish(bearish)
                .emaFast(emaFast)
                .emaSlow(emaSlow)
                .rsi(rsi)
                .atr(atr)
                .trendStrength(trendStrength)
                .build();
    }

    // === Индикаторы =======================================================

    private double ema(List<Double> values, int period) {
        if (values.isEmpty()) return 0;
        double k = 2.0 / (period + 1);
        double ema = values.getFirst();
        for (int i = 1; i < values.size(); i++) {
            ema = values.get(i) * k + ema * (1 - k);
        }
        return ema;
    }

    private double rsi(List<Double> closes, int period) {
        if (closes.size() < period + 1) return 50;
        double gain = 0, loss = 0;
        for (int i = closes.size() - period; i < closes.size(); i++) {
            double change = closes.get(i) - closes.get(i - 1);
            if (change > 0) gain += change;
            else loss -= change;
        }
        double avgGain = gain / period;
        double avgLoss = loss / period;
        double rs = avgLoss == 0 ? 0 : avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    private double atr(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        if (highs.size() < 2) return 0;
        List<Double> trList = new ArrayList<>();
        for (int i = 1; i < highs.size(); i++) {
            double high = highs.get(i);
            double low = lows.get(i);
            double prevClose = closes.get(i - 1);
            double tr = Math.max(high - low,
                    Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            trList.add(tr);
        }
        return trList.stream().skip(Math.max(0, trList.size() - period))
                .mapToDouble(Double::doubleValue)
                .average().orElse(0.0);
    }
}
