package com.chicu.aitradebot.strategy.core.impl;

import com.chicu.aitradebot.market.MarketStreamManager;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * CandleProvider, который отдаёт свечи стратегиям,
 * конвертируя MarketStreamManager → CandleProvider.Candle.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandleProviderImpl implements CandleProvider {

    private final MarketStreamManager manager;

    @Override
    public List<CandleProvider.Candle> getRecentCandles(
            long chatId,
            String symbol,
            String timeframe,
            int limit
    ) {
        try {
            List<com.chicu.aitradebot.market.model.Candle> raw = manager.getCandles(symbol, timeframe, limit);

            return raw.stream()
                    .map(c -> new CandleProvider.Candle(
                            c.getTime(),
                            c.getOpen(),
                            c.getHigh(),
                            c.getLow(),
                            c.getClose(),
                            c.getVolume()
                    ))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("❌ Ошибка получения свечей {} {}: {}", symbol, timeframe, e.getMessage());
            return List.of();
        }
    }
}
