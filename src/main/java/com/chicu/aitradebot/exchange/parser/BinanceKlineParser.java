package com.chicu.aitradebot.exchange.parser;

import com.chicu.aitradebot.market.model.UnifiedKline;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Slf4j
@Component
public class BinanceKlineParser {

    /**
     * Поддерживает 2 формата Binance WS:
     * 1) Combined stream: { "stream": "...", "data": { "e":"kline", "k": {...} } }
     * 2) Direct stream:   { "e":"kline", "k": {...} }
     *
     * Возвращает null, если это не kline-сообщение.
     */
    public UnifiedKline parse(JSONObject root) {
        if (root == null) return null;

        try {
            // combined stream -> берем data, иначе считаем что root уже payload
            JSONObject payload = root.has("data") && root.opt("data") instanceof JSONObject
                    ? root.getJSONObject("data")
                    : root;

            // не kline? -> тихо игнорируем
            // (иногда прилетают другие события или сервисные сообщения)
            JSONObject k = payload.optJSONObject("k");
            if (k == null) {
                // иногда k может быть внутри data/data (на всякий)
                JSONObject innerData = payload.optJSONObject("data");
                if (innerData != null) k = innerData.optJSONObject("k");
            }
            if (k == null) return null;

            // Базовая проверка события (если есть)
            String eventType = payload.optString("e", "");
            if (!eventType.isBlank() && !"kline".equalsIgnoreCase(eventType)) {
                return null;
            }

            String symbol = firstNonBlank(
                    k.optString("s", null),
                    payload.optString("s", null),
                    payload.optString("symbol", null)
            );

            String interval = firstNonBlank(
                    k.optString("i", null),
                    payload.optString("i", null),
                    payload.optString("interval", null)
            );

            long openTime  = optLong(k, "t");
            long closeTime = optLong(k, "T");

            BigDecimal open   = optBd(k, "o");
            BigDecimal high   = optBd(k, "h");
            BigDecimal low    = optBd(k, "l");
            BigDecimal close  = optBd(k, "c");
            BigDecimal volume = optBd(k, "v");

            boolean closed = k.optBoolean("x", false);

            if (openTime <= 0 || close == null) return null;
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
            // не спамим
            log.warn("⚠ BinanceKlineParser error: {}", e.getMessage());
            return null;
        }
    }

    public UnifiedKline parseKline(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return null;
        return parse(new org.json.JSONObject(jsonText));
    }

    private static long optLong(JSONObject o, String key) {
        if (o == null || key == null || !o.has(key) || o.isNull(key)) return 0L;
        Object v = o.get(key);
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v).trim()); }
        catch (Exception ignored) { return 0L; }
    }

    private static BigDecimal optBd(JSONObject o, String key) {
        if (o == null || key == null || !o.has(key) || o.isNull(key)) return null;
        String s = String.valueOf(o.get(key)).trim();
        if (s.isBlank()) return null;
        try { return new BigDecimal(s); }
        catch (Exception ignored) { return null; }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }
}
