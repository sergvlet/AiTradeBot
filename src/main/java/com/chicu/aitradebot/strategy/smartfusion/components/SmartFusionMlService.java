package com.chicu.aitradebot.strategy.smartfusion.components;

import com.chicu.aitradebot.strategy.core.CandleProvider;
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
    public boolean confirmBuy(SmartFusionFilter.FilterSignal signal,
                              SmartFusionStrategySettings cfg,
                              List<CandleProvider.Candle> candles) {

        if (candles.isEmpty()) return false;

        double[] bb = bollingerBands(candles, cfg.getBollingerPeriod(), cfg.getBollingerK());
        double lastClose = candles.get(candles.size() - 1).close();

        boolean breakout = lastClose > bb[1]; // пробитие нижней границы вверх
        double mlConfidence = mockMlPredict(candles, "BUY");

        boolean confirmed = breakout && mlConfidence >= cfg.getMlBuyMin();
        if (confirmed) {
            log.info("🤖 ML CONFIRM BUY OK (breakout={}, conf={})", breakout, mlConfidence);
        }
        return confirmed;
    }

    /**
     * Проверяет сигнал на продажу:
     * - цена пробивает верхнюю границу Bollinger вниз
     * - ML-модель подтверждает SELL > minConfidence
     */
    public boolean confirmSell(SmartFusionFilter.FilterSignal signal,
                               SmartFusionStrategySettings cfg,
                               List<CandleProvider.Candle> candles) {

        if (candles.isEmpty()) return false;

        double[] bb = bollingerBands(candles, cfg.getBollingerPeriod(), cfg.getBollingerK());
        double lastClose = candles.get(candles.size() - 1).close();

        boolean breakout = lastClose < bb[0]; // пробитие верхней границы вниз
        double mlConfidence = mockMlPredict(candles, "SELL");

        boolean confirmed = breakout && mlConfidence >= cfg.getMlSellMin();
        if (confirmed) {
            log.info("🤖 ML CONFIRM SELL OK (breakout={}, conf={})", breakout, mlConfidence);
        }
        return confirmed;
    }

    /**
     * Простая модель Bollinger Bands
     * @return [upper, lower]
     */
    private double[] bollingerBands(List<CandleProvider.Candle> candles, int period, double k) {
        int n = candles.size();
        if (n < period) period = n;

        List<Double> closes = candles.subList(n - period, n)
                .stream()
                .map(CandleProvider.Candle::close)
                .toList();

        double avg = closes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = closes.stream()
                .mapToDouble(c -> Math.pow(c - avg, 2))
                .average()
                .orElse(0.0);
        double std = Math.sqrt(variance);

        double upper = avg + k * std;
        double lower = avg - k * std;
        return new double[]{upper, lower};
    }

    /**
     * Заглушка ML-модели.
     * TODO: заменить на реальный вызов Python-сервиса / Onnx-модели.
     */
    private double mockMlPredict(List<CandleProvider.Candle> candles, String side) {
        // Пока просто "симпатичная" заглушка: чем длиннее тренд в нужную сторону,
        // тем выше "уверенность".
        if (candles.size() < 5) return 0.5;

        int n = candles.size();
        double last = candles.get(n - 1).close();
        double prev = candles.get(n - 5).close();
        double changePct = (last - prev) / prev * 100.0;

        double base = 0.5;
        if ("BUY".equalsIgnoreCase(side) && changePct > 0) {
            base += Math.min(changePct / 10.0, 0.4); // до +0.4
        } else if ("SELL".equalsIgnoreCase(side) && changePct < 0) {
            base += Math.min(-changePct / 10.0, 0.4);
        }

        double result = Math.max(0.0, Math.min(1.0, base));
        log.debug("🤖 mockMlPredict side={} changePct={} → conf={}", side, changePct, result);
        return result;
    }
}
