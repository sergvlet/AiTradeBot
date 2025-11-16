package com.chicu.aitradebot.web.controller.api;

import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

@RestController
@RequestMapping("/api/test")
public class TestChartHistoryController {

    @GetMapping("/chart")
    public Map<String, Object> getChart(
            @RequestParam long chatId,
            @RequestParam String symbol,
            @RequestParam(defaultValue = "15m") String timeframe
    ) throws Exception {

        // --- MOCK candles ---
        List<Map<String, Object>> candles = new ArrayList<>();
        long now = System.currentTimeMillis();
        Random rnd = new Random(42);

        double price = 68000;

        for (int i = 0; i < 100; i++) {
            double open = price + rnd.nextDouble() * 200 - 100;
            double close = open + rnd.nextDouble() * 200 - 100;
            double high = Math.max(open, close) + rnd.nextDouble() * 50;
            double low = Math.min(open, close) - rnd.nextDouble() * 50;

            long ts = now - (100 - i) * 60_000;

            Map<String, Object> c = new HashMap<>();
            c.put("time", ts);
            c.put("open", open);
            c.put("high", high);
            c.put("low", low);
            c.put("close", close);
            candles.add(c);

            price = close;
        }

        // --- MOCK EMAs ---
        List<Map<String, Object>> emaFast = new ArrayList<>();
        List<Map<String, Object>> emaSlow = new ArrayList<>();

        for (Map<String, Object> c : candles) {
            long t = (long) c.get("time");
            double close = (double) c.get("close");

            emaFast.add(Map.of("time", t, "value", close * 0.999));
            emaSlow.add(Map.of("time", t, "value", close * 1.001));
        }

        // --- TEST trades ---
        String jsonTrades = """
        [
            {
                "id":7,"chatId":1,"userId":1,"symbol":"BTCUSDT","side":"SELL",
                "price":68500,"quantity":0.0015,"total":102.75,
                "strategyType":"SCALPING","status":"FILLED","filled":true,
                "timestamp":1763093564768,
                "entryReason":"SCALP SHORT BREAKOUT","exitReason":"SL HIT",
                "takeProfitPrice":67500,"stopLossPrice":69000,
                "exitPrice":69000,"exitTimestamp":1763179964768,
                "realizedPnlUsd":-1.2,"realizedPnlPct":-1.167,
                "tpHit":false,"slHit":true,"mlConfidence":0.64231
            },
            {
                "id":6,"chatId":1,"userId":1,"symbol":"BTCUSDT","side":"BUY",
                "price":68000,"quantity":0.002,"total":136,
                "strategyType":"SMART_FUSION","status":"FILLED","filled":true,
                "timestamp":1763007164768,
                "entryReason":"RSI<30 + EMA CROSS","exitReason":"TP HIT",
                "takeProfitPrice":69000,"stopLossPrice":67000,
                "exitPrice":69000,"exitTimestamp":1763093564768,
                "realizedPnlUsd":2,"realizedPnlPct":1.47,
                "tpHit":true,"slHit":false,"mlConfidence":0.87321
            }
        ]
        """;

        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> trades = mapper.readValue(jsonTrades, List.class);

        return Map.of(
                "symbol", symbol,
                "timeframe", timeframe,
                "candles", candles,
                "emaFast", emaFast,
                "emaSlow", emaSlow,
                "trades", trades
        );
    }
}
