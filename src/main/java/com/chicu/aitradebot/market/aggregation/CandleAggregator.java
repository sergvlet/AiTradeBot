package com.chicu.aitradebot.market.aggregation;

import com.chicu.aitradebot.strategy.core.CandleProvider;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CandleAggregator {

    public CandleProvider.Candle parseKline(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONObject k = obj.getJSONObject("k");

            return new CandleProvider.Candle(
                    k.getLong("t"),
                    k.getDouble("o"),
                    k.getDouble("h"),
                    k.getDouble("l"),
                    k.getDouble("c"),
                    k.getDouble("v")
            );

        } catch (Exception e) {
            log.error("❌ parseKline error: {}", e.getMessage());
            return null;
        }
    }
}
