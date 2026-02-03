package com.chicu.aitradebot.ai.ml;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * MlFeatures
 * ==========
 * Минимальный набор признаков для XGBoost/ML классификатора.
 *
 * Важно:
 * - Сделано БЕЗ жёсткой зависимости от твоего класса свечи.
 * - CandleProvider может возвращать разные типы (UnifiedKline, Candle, Map и т.д.)
 *   -> тут извлекаем OHLCV через reflection/Map.
 *
 * Признаки:
 * - momentum1: относительное изменение цены (close / prevClose - 1)
 * - volatilityPct: (maxHigh - minLow) / lastClose  (в процентах доли, т.е. 0.012 = 1.2%)
 * - volumeRel: текущий объём / средний объём
 * - smaFastRel/smaSlowRel: SMA к lastClose (отклонение)
 */
public record MlFeatures(
        Double momentum1,
        Double volatilityPct,
        Double volumeRel,
        Double smaFastRel,
        Double smaSlowRel
) {

    public static MlFeatures fromCandles(List<?> candles, BigDecimal lastPrice) {
        if (candles == null || candles.size() < 5) {
            return new MlFeatures(0.0, 0.0, 1.0, 0.0, 0.0);
        }

        int n = candles.size();

        // берём close/volume с конца
        Double lastClose = getClose(candles.get(n - 1));
        Double prevClose = (n >= 2) ? getClose(candles.get(n - 2)) : null;

        if (lastClose == null && lastPrice != null) lastClose = lastPrice.doubleValue();

        // momentum1
        double mom = 0.0;
        if (lastClose != null && prevClose != null && prevClose != 0.0) {
            mom = (lastClose / prevClose) - 1.0;
        }

        // volatilityPct: (maxHigh - minLow)/lastClose
        double maxH = Double.NEGATIVE_INFINITY;
        double minL = Double.POSITIVE_INFINITY;

        for (Object c : candles) {
            Double h = getHigh(c);
            Double l = getLow(c);
            if (h != null) maxH = Math.max(maxH, h);
            if (l != null) minL = Math.min(minL, l);
        }

        double vol = 0.0;
        if (Double.isFinite(maxH) && Double.isFinite(minL) && lastClose != null && lastClose > 0) {
            vol = (maxH - minL) / lastClose;
        }

        // объём: текущий / средний
        Double lastVol = getVolume(candles.get(n - 1));
        double avgVol = 0.0;
        int volCnt = 0;
        for (Object c : candles) {
            Double v = getVolume(c);
            if (v != null && Double.isFinite(v)) {
                avgVol += v;
                volCnt++;
            }
        }
        avgVol = volCnt > 0 ? (avgVol / volCnt) : 0.0;
        double volRel = (lastVol != null && avgVol > 0.0) ? (lastVol / avgVol) : 1.0;

        // SMA fast/slow как отклонение от lastClose
        double smaFastRel = 0.0;
        double smaSlowRel = 0.0;

        if (lastClose != null && lastClose > 0.0) {
            double smaFast = smaClose(candles, 10);
            double smaSlow = smaClose(candles, 30);

            if (smaFast > 0) smaFastRel = (smaFast / lastClose) - 1.0;
            if (smaSlow > 0) smaSlowRel = (smaSlow / lastClose) - 1.0;
        }

        return new MlFeatures(
                clamp(mom, -1.0, 1.0),
                clamp(vol, 0.0, 5.0),
                clamp(volRel, 0.0, 1000.0),
                clamp(smaFastRel, -1.0, 1.0),
                clamp(smaSlowRel, -1.0, 1.0)
        );
    }

    // -------------------------
    // helpers
    // -------------------------

    private static double smaClose(List<?> candles, int period) {
        if (candles == null || candles.isEmpty()) return 0.0;
        int n = candles.size();
        int from = Math.max(0, n - period);
        double sum = 0.0;
        int cnt = 0;
        for (int i = from; i < n; i++) {
            Double c = getClose(candles.get(i));
            if (c != null && Double.isFinite(c)) {
                sum += c;
                cnt++;
            }
        }
        return cnt > 0 ? sum / cnt : 0.0;
    }

    private static double clamp(double v, double lo, double hi) {
        if (!Double.isFinite(v)) return lo;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static Double getClose(Object candle) {
        return readDouble(candle, "getClose", "close", "getC", "c");
    }

    private static Double getHigh(Object candle) {
        return readDouble(candle, "getHigh", "high", "getH", "h");
    }

    private static Double getLow(Object candle) {
        return readDouble(candle, "getLow", "low", "getL", "l");
    }

    private static Double getVolume(Object candle) {
        return readDouble(candle, "getVolume", "volume", "getV", "v");
    }

    @SuppressWarnings("unchecked")
    private static Double readDouble(Object obj, String... names) {
        if (obj == null) return null;

        // Map-представление свечи
        if (obj instanceof Map<?, ?> m) {
            for (String name : names) {
                Object v = m.get(name);
                if (v == null) {
                    // часто в Map ключи: "close","high","low","volume"
                    v = m.get(normalizeKey(name));
                }
                Double d = toDouble(v);
                if (d != null) return d;
            }
            return null;
        }

        // reflection по методам
        Class<?> cl = obj.getClass();
        for (String name : names) {
            try {
                Method method = cl.getMethod(name);
                Object v = method.invoke(obj);
                Double d = toDouble(v);
                if (d != null) return d;
            } catch (Exception ignored) {}
        }

        return null;
    }

    private static String normalizeKey(String name) {
        // getClose -> close
        if (name.startsWith("get") && name.length() > 3) {
            String x = name.substring(3);
            return x.substring(0, 1).toLowerCase(Locale.ROOT) + x.substring(1);
        }
        return name;
    }

    private static Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Double d) return d;
        if (v instanceof Float f) return (double) f;
        if (v instanceof Integer i) return (double) i;
        if (v instanceof Long l) return (double) l;
        if (v instanceof BigDecimal bd) return bd.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (Exception ignored) { return null; }
        }
        return null;
    }
}
