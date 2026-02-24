package com.chicu.aitradebot.strategy.ml;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Минимальный набор признаков для классификации.
 * Сделано “по-проду”: безопасно к типам свечей (reflection),
 * без жёсткой привязки к модели Candle.
 */
public record MlFeatures(
        Double momentum1,
        Double volatilityPct,
        Double volumeRel,
        Double smaFastRel,
        Double smaSlowRel
) {
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("momentum1", momentum1);
        m.put("volatilityPct", volatilityPct);
        m.put("volumeRel", volumeRel);
        m.put("smaFastRel", smaFastRel);
        m.put("smaSlowRel", smaSlowRel);
        return m;
    }

    public static MlFeatures fromCandles(List<?> candles, BigDecimal lastPrice) {
        if (candles == null || candles.isEmpty()) {
            return new MlFeatures(null, null, null, null, null);
        }

        double[] closes = extractCloses(candles, lastPrice);
        if (closes.length < 2) {
            return new MlFeatures(null, null, null, null, null);
        }

        double last = closes[closes.length - 1];
        double prev = closes[closes.length - 2];

        Double momentum1 = null;
        if (prev > 0 && last > 0) {
            momentum1 = (last / prev) - 1.0;
        }

        Double volPct = stddevReturnsPct(closes);
        Double smaFastRel = smaRel(closes, last, Math.max(2, closes.length / 10));
        Double smaSlowRel = smaRel(closes, last, Math.max(3, closes.length / 3));

        return new MlFeatures(momentum1, volPct, null, smaFastRel, smaSlowRel);
    }

    private static double[] extractCloses(List<?> candles, BigDecimal lastPrice) {
        List<Double> out = new ArrayList<>(candles.size() + 1);

        for (Object c : candles) {
            Double close = readAsDouble(c, "getClose", "close", "getC", "getClosePrice");
            if (close != null && Double.isFinite(close) && close > 0) out.add(close);
        }

        if (lastPrice != null && lastPrice.signum() > 0) {
            out.add(lastPrice.doubleValue());
        }

        double[] arr = new double[out.size()];
        for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
        return arr;
    }

    private static Double readAsDouble(Object target, String... methods) {
        if (target == null) return null;

        for (String name : methods) {
            try {
                Method m = target.getClass().getMethod(name);
                Object v = m.invoke(target);
                if (v == null) continue;

                if (v instanceof BigDecimal bd) return bd.doubleValue();
                if (v instanceof Number n) return n.doubleValue();

                String s = String.valueOf(v).trim();
                if (s.isEmpty()) continue;
                return Double.parseDouble(s);

            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Double smaRel(double[] closes, double last, int period) {
        if (closes == null || closes.length == 0) return null;
        int p = Math.min(period, closes.length);
        if (p <= 0) return null;

        double sum = 0.0;
        for (int i = closes.length - p; i < closes.length; i++) sum += closes[i];
        double sma = sum / p;
        if (sma <= 0 || last <= 0) return null;
        return (last / sma) - 1.0;
    }

    private static Double stddevReturnsPct(double[] closes) {
        if (closes == null || closes.length < 3) return null;

        int n = closes.length - 1;
        double[] r = new double[n];
        int k = 0;

        for (int i = 1; i < closes.length; i++) {
            double a = closes[i - 1];
            double b = closes[i];
            if (a <= 0 || b <= 0) continue;
            r[k++] = (b / a) - 1.0;
        }

        if (k < 2) return null;

        double mean = 0.0;
        for (int i = 0; i < k; i++) mean += r[i];
        mean /= k;

        double var = 0.0;
        for (int i = 0; i < k; i++) {
            double d = r[i] - mean;
            var += d * d;
        }
        var /= (k - 1);

        return Math.sqrt(var) * 100.0;
    }
}
