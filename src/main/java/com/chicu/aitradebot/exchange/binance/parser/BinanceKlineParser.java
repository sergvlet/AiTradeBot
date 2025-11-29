package com.chicu.aitradebot.exchange.binance.parser;

import com.chicu.aitradebot.market.model.Candle;
import org.json.JSONObject;

public class BinanceKlineParser {

    public static Candle parse(String json) {
        JSONObject obj = new JSONObject(json);

        JSONObject k;

        // Формат Futures WS:  {"stream":"...", "data": { "k": {...} } }
        if (obj.has("data") && obj.getJSONObject("data").has("k")) {
            k = obj.getJSONObject("data").getJSONObject("k");
        }
        // Формат REST или spot WS: { "k": {...} }
        else if (obj.has("k")) {
            k = obj.getJSONObject("k");
        }
        else {
            return null;
        }

        return new Candle(
                k.getLong("t"),      // open time
                k.getDouble("o"),
                k.getDouble("h"),
                k.getDouble("l"),
                k.getDouble("c"),
                k.getDouble("v"),
                k.getBoolean("x")
        );
    }
}
