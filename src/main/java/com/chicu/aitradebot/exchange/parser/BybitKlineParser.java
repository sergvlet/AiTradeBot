package com.chicu.aitradebot.exchange.parser;

import com.chicu.aitradebot.market.model.UnifiedKline;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;

@Slf4j
public class BybitKlineParser {

    /**
     * Универсальный парсер Bybit WS kline -> UnifiedKline.
     * Поддерживает частые формы:
     * - { topic, data: {...} }
     * - { topic, data: [ {...} ] }
     * - { data: { kline: {...} } } / { data: { candles: [...] } }
     * - числа могут быть строками или number
     */
    public UnifiedKline parse(JSONObject root) {
        if (root == null) return null;

        try {
            JSONObject payload = unwrap(root);
            if (payload == null) return null;

            // Если внутри лежит массив свечей — берем первую (обычно приходит 1 свеча)
            if (payload.has("candles")) {
                Object c = payload.get("candles");
                JSONObject candle = firstObject(c);
                if (candle != null) payload = candle;
            }

            if (payload.has("kline")) {
                Object k = payload.get("kline");
                JSONObject kObj = firstObject(k);
                if (kObj != null) payload = kObj;
            }

            // === symbol / interval ===
            String symbol = firstNonBlank(
                    optStr(payload, "symbol"),
                    optStr(payload, "s")
            );

            String interval = firstNonBlank(
                    optStr(payload, "interval"),
                    optStr(payload, "klineType"),
                    optStr(payload, "i")
            );

            // === timestamps ===
            // Bybit часто: start, end / startTime, endTime / t, T / timestamp
            long openTime = firstPositive(
                    optLong(payload, "start"),
                    optLong(payload, "startTime"),
                    optLong(payload, "t"),
                    optLong(payload, "timestamp")
            );

            long closeTime = firstPositive(
                    optLong(payload, "end"),
                    optLong(payload, "endTime"),
                    optLong(payload, "T")
            );

            // === OHLCV ===
            BigDecimal open = firstBd(payload, "open", "o");
            BigDecimal high = firstBd(payload, "high", "h");
            BigDecimal low  = firstBd(payload, "low",  "l");
            BigDecimal close= firstBd(payload, "close","c");
            BigDecimal volume = firstBd(payload, "volume", "v", "turnoverVolume");

            // === closed flag ===
            // Bybit: confirm=true когда свеча закрыта
            boolean closed = firstBool(payload,
                    "confirm",     // v5
                    "x",           // бинанс-стиль иногда
                    "isClosed"
            );

            // Мини-валидация
            if (openTime <= 0 || close == null) return null;

            // если closeTime не пришел — попробуем вычислить грубо по openTime (не идеально, но лучше чем 0)
            // Хардкод интервала НЕ используем: просто оставим 0, если реально нет.
            // (Если хочешь — можно добавить IntervalService из базы, но тут парсер должен быть тупой.)
            if (closeTime <= 0) closeTime = openTime;

            return UnifiedKline.builder()
                    .symbol(symbol)
                    .timeframe(interval)
                    .openTime(openTime)
                    .closeTime(closeTime)
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .closed(closed)
                    .build();

        } catch (Exception e) {
            // не спамим трейсами
            log.warn("⚠ BybitKlineParser error: {}", e.getMessage());
            return null;
        }
    }

    // =====================================================================
    // UNWRAP
    // =====================================================================

    /**
     * Достаём реальный payload со свечой из разных обёрток.
     */
    private static JSONObject unwrap(JSONObject root) {
        // 1) чаще всего Bybit: { topic, data: ... }
        if (root.has("data")) {
            Object data = root.get("data");
            JSONObject obj = firstObject(data);
            if (obj != null) return obj;

            // если data - массив массивов/строк — попробуем распарсить
            if (data instanceof JSONArray arr && arr.length() > 0) {
                Object first = arr.get(0);
                JSONObject o = firstObject(first);
                if (o != null) return o;
            }
        }

        // 2) иногда: { result: { ... } }
        if (root.has("result")) {
            Object r = root.get("result");
            JSONObject obj = firstObject(r);
            if (obj != null) return obj;
        }

        // 3) либо это уже payload
        return root;
    }

    private static JSONObject firstObject(Object v) {
        if (v == null) return null;
        if (v instanceof JSONObject jo) return jo;
        if (v instanceof JSONArray arr) {
            if (arr.length() == 0) return null;
            Object first = arr.get(0);
            return firstObject(first);
        }
        if (v instanceof String s) {
            String t = s.trim();
            if (t.startsWith("{") && t.endsWith("}")) {
                return new JSONObject(t);
            }
        }
        return null;
    }

    // =====================================================================
    // SAFE OPTS
    // =====================================================================

    private static String optStr(JSONObject o, String key) {
        if (o == null || key == null || !o.has(key) || o.isNull(key)) return null;
        Object v = o.get(key);
        String s = String.valueOf(v);
        return s != null ? s.trim() : null;
    }

    private static long optLong(JSONObject o, String key) {
        if (o == null || key == null || !o.has(key) || o.isNull(key)) return 0L;
        Object v = o.get(key);
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static BigDecimal optBd(JSONObject o, String key) {
        String s = optStr(o, key);
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static BigDecimal firstBd(JSONObject o, String... keys) {
        for (String k : keys) {
            BigDecimal v = optBd(o, k);
            if (v != null) return v;
        }
        return null;
    }

    private static boolean firstBool(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o == null || k == null || !o.has(k) || o.isNull(k)) continue;
            Object v = o.get(k);
            if (v instanceof Boolean b) return b;
            String s = String.valueOf(v).trim().toLowerCase();
            if ("true".equals(s) || "1".equals(s)) return true;
            if ("false".equals(s) || "0".equals(s)) return false;
        }
        return false;
    }

    private static long firstPositive(long... values) {
        for (long v : values) if (v > 0) return v;
        return 0L;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
