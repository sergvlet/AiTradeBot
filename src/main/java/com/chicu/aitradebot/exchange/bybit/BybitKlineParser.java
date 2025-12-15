package com.chicu.aitradebot.exchange.bybit;

import com.chicu.aitradebot.market.model.UnifiedKline;
import org.json.JSONObject;

import java.math.BigDecimal;

/**
 * Парсер Bybit kline → UnifiedKline.
 *
 * Структуры Bybit отличаются по версии API, тут общий пример.
 * Подкорректируешь поля под свой реальный JSON.
 */
public class BybitKlineParser {

    /**
     * @param root объект верхнего уровня JSON (уже распарсенный).
     */
    public UnifiedKline parse(JSONObject root) {
        // Часто Bybit присылает: { "topic": "...", "data": { ... } }
        JSONObject data = root.has("data")
                ? root.getJSONObject("data")
                : root;

        return UnifiedKline.builder()
                .symbol(data.getString("symbol"))
                .timeframe(data.optString("interval", data.optString("klineType", "1m")))
                .openTime(data.getLong("start")) // или "startTime" / "t" — подправишь по факту
                .open(new BigDecimal(data.getString("open")))
                .high(new BigDecimal(data.getString("high")))
                .low(new BigDecimal(data.getString("low")))
                .close(new BigDecimal(data.getString("close")))
                .volume(new BigDecimal(data.getString("volume")))
                .build();
    }
}
