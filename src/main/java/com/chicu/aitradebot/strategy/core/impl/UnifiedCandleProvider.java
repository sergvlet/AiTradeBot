package com.chicu.aitradebot.strategy.core.impl;

import com.chicu.aitradebot.market.MarketService;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class UnifiedCandleProvider implements CandleProvider {

    private final MarketService marketService;

    @Override
    public List<Candle> getRecentCandles(
            long chatId,          // ✔ правильный тип
            String symbol,
            String timeframe,
            int limit
    ) {
        return marketService.loadCandles(chatId, symbol, timeframe, limit);
    }
}
