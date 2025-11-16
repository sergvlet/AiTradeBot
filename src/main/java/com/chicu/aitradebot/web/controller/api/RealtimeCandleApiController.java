package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.market.CandleStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/chart")
@RequiredArgsConstructor
public class RealtimeCandleApiController {

    private final CandleStreamService candleStream;

    @GetMapping("/realtime")
    public Map<String, Object> loadRealtime(@RequestParam long chatId) {

        List<ExchangeClient.Kline> klines = candleStream.getSmartFusionCandles(chatId);

        List<Map<String, Object>> arr = new ArrayList<>();

        for (ExchangeClient.Kline k : klines) {
            Map<String, Object> c = new HashMap<>();
            c.put("time", k.openTime());
            c.put("open", k.open());
            c.put("high", k.high());
            c.put("low", k.low());
            c.put("close", k.close());
            arr.add(c);
        }

        return Map.of("candles", arr);
    }
}
