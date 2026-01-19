package com.chicu.aitradebot.exchange.binance.parser;

import com.chicu.aitradebot.market.model.UnifiedKline;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Slf4j
@Component
public class BinanceKlineParser {

    public UnifiedKline parse(String rawJson) {
        try {
            JSONObject root = new JSONObject(rawJson);
            JSONObject data = root.getJSONObject("data");
            JSONObject k = data.getJSONObject("k");

            return UnifiedKline.builder()
                    .symbol(k.getString("s"))
                    .timeframe(k.getString("i"))
                    .openTime(k.getLong("t"))
                    .closeTime(k.getLong("T"))
                    .open(new BigDecimal(k.getString("o")))
                    .high(new BigDecimal(k.getString("h")))
                    .low(new BigDecimal(k.getString("l")))
                    .close(new BigDecimal(k.getString("c")))
                    .volume(new BigDecimal(k.getString("v")))
                    .closed(k.getBoolean("x")) // 🔥 ВАЖНО
                    .build();

        } catch (Exception e) {
            log.warn("⚠ BinanceKlineParser error: {}", e.getMessage());
            return null;
        }
    }
}
