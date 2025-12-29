package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.strategy.core.CandleProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class DebugCandleProviderController {

    private final CandleProvider candleProvider;

    @GetMapping("/api/debug/candles")
    public Object debugCandles(
            @RequestParam long chatId,
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1m") String timeframe,
            @RequestParam(defaultValue = "50") int limit
    ) {

        log.warn("🧪 DEBUG CandleProvider: chatId={} symbol={} tf={} limit={}",
                chatId, symbol, timeframe, limit);

        var candles = candleProvider.getRecentCandles(
                chatId,
                symbol.toUpperCase(),
                timeframe,
                limit
        );

        log.warn("🧪 RESULT candles size={}", candles == null ? -1 : candles.size());

        return candles;
    }
}
