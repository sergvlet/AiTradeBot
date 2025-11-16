package com.chicu.aitradebot.strategy.smartfusion.components;

import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SmartFusion ML Confirm v3.1
 * Проверяет импульсы Bollinger и подтверждает сигналы нейромоделью.
 */
@Component
@Slf4j
public class SmartFusionMlService {

    /**
     * Проверяет сигнал на покупку:
     * - цена пробивает нижнюю границу Bollinger вверх
     * - ML-модель подтверждает BUY > minConfidence
     */
    public boolean confirmBuy(SmartFusionFilter.FilterSignal signal, SmartFusionStrategySettings cfg,
                              List<SmartFusionCandleService.Candle> candles) {

        if (candles.isEmpty()) return false;
        double[] bb = bollingerBands(candles, cfg.getBollingerPeriod(), cfg.getBollingerK());
        double lastClose = candles.get(candles.size() - 1).close();

        boolean breakout = lastClose > bb[1]; // пробитие нижней границы вверх
        double mlConfidence = mockMlPredict(candles, "BUY");

        boolean confirmed = breakout && mlConfidence >= cfg.getMlBuyMin();
        if (confirmed)
            log.info("🤖 ML CONFIRM BUY OK (breakout={}, conf={})", breakout, mlConfidence);
        return confirmed;
    }

    /**
     * Проверяет сигнал на продажу:
     * - цена пробивает верхнюю границу Bollinger вниз
     * - ML-модель подтверждает SELL > minConfidence
     */
    public boolean confirmSell(SmartFusionFilter.FilterSignal signal, SmartFusionStrategySettings cfg,
                               List<SmartFusionCandleService.Candle> candles) {

        if (candles.isEmpty()) return false;
        double[] bb = bollingerBands(candles, cfg.getBollingerPeriod(), cfg.getBollingerK());
        double lastClose = candles.get(candles.size() - 1).close();

        boolean breakout = lastClose < bb[0]; // пробитие верхней границы вниз
        double mlConfidence = mockMlPredict(candles, "SELL");

        boolean confirmed = breakout && mlConfidence >= cfg.getMlSellMin();
        if (confirmed)
            log.info("🤖 ML CONFIRM SELL OK (breakout={}, conf={})", breakout, mlConfidence);
        return confirmed;
    }

    /**
     * Простая модель Bollinger Bands
     * @return [upper, lower]
     */
    private double[] bollingerBands(List<SmartFusionCandleService.Candle> candles, int period, double k) {
        int n = candles.size();
        if (n < period) period = n;
        List<Double> closes = candles.subList(n - period, n)
                .stream().map(SmartFusionCandleService.Candle::close).toList();

        double avg = closes.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = closes.stream()
                .mapToDouble(c -> Math.pow(c - avg, 2))
                .average().orElse(0);
        double stdDev = Math.sqrt(variance);

        double upper = avg + k * stdDev;
        double lower = avg - k * stdDev;
        return new double[]{upper, lower};
    }

    /**
     * Временный ML-эмулятор.
     * Позже заменим вызовом Python/XGBoost.
     */
    private double mockMlPredict(List<SmartFusionCandleService.Candle> candles, String direction) {
        double rnd = Math.random();
        return switch (direction) {
            case "BUY" -> 0.5 + rnd * 0.5;  // 0.5–1.0
            case "SELL" -> 0.4 + rnd * 0.4; // 0.4–0.8
            default -> 0.5;
        };
    }
}
