package com.chicu.aitradebot.web.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/test")
public class TestTradesController {

    @GetMapping("/trades")
    public List getTrades(
            @RequestParam long chatId,
            @RequestParam String symbol
    ) throws Exception {

        System.out.println("🔥 TestTradesController active: chatId=" + chatId + ", symbol=" + symbol);

        String json = """
        [
            {
                "id":7, "chatId":1, "userId":1, "symbol":"BTCUSDT", "side":"SELL",
                "price":68500, "quantity":0.0015, "total":102.75,
                "strategyType":"SCALPING", "status":"FILLED", "filled":true,
                "timestamp":1763093564768,
                "entryReason":"SCALP SHORT BREAKOUT",
                "exitReason":"SL HIT",
                "takeProfitPrice":67500,
                "stopLossPrice":69000,
                "exitPrice":69000,
                "exitTimestamp":1763179964768,
                "realizedPnlUsd":-1.2,
                "realizedPnlPct":-1.167,
                "tpHit":false, "slHit":true, "mlConfidence":0.64231
            },
            {
                "id":6, "chatId":1, "userId":1, "symbol":"BTCUSDT", "side":"BUY",
                "price":68000, "quantity":0.002, "total":136,
                "strategyType":"SMART_FUSION", "status":"FILLED", "filled":true,
                "timestamp":1763007164768,
                "entryReason":"RSI<30 + EMA CROSS",
                "exitReason":"TP HIT",
                "takeProfitPrice":69000,
                "stopLossPrice":67000,
                "exitPrice":69000,
                "exitTimestamp":1763093564768,
                "realizedPnlUsd":2,
                "realizedPnlPct":1.47,
                "tpHit":true, "slHit":false, "mlConfidence":0.87321
            }
        ]
        """;

        return new ObjectMapper().readValue(json, List.class);
    }
}
