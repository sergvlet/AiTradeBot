package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.market.MarketService;
import com.chicu.aitradebot.web.facade.WebMarketFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * WebMarketFacadeImpl — мост между Web и MarketService.
 * Здесь НЕ должно быть:
 *  - BinanceExchangeClient
 *  - CandleService
 *  - SmartFusionCandleService
 *  - прямых вызовов стратегий
 * Только MarketService.
 */
@Service
@RequiredArgsConstructor
public class WebMarketFacadeImpl implements WebMarketFacade {

    private final MarketService marketService;

    @Override
    public List<CandlePoint> loadInitialCandles(
            Long chatId,
            String symbol,
            String timeframe,
            int limit
    ) {
        return marketService
                .loadCandles(chatId, symbol, timeframe, limit)
                .stream()
                .map(c -> new CandlePoint(
                        c.time(),
                        BigDecimal.valueOf(c.open()),
                        BigDecimal.valueOf(c.high()),
                        BigDecimal.valueOf(c.low()),
                        BigDecimal.valueOf(c.close()),
                        BigDecimal.valueOf(c.volume())
                ))
                .toList();
    }

    @Override
    public List<CandlePoint> loadMoreCandles(
            Long chatId,
            String symbol,
            String timeframe,
            Instant to,
            int limit
    ) {
        return marketService
                .loadMoreCandles(chatId, symbol, timeframe, to, limit)
                .stream()
                .map(c -> new CandlePoint(
                        c.time(),
                        BigDecimal.valueOf(c.open()),
                        BigDecimal.valueOf(c.high()),
                        BigDecimal.valueOf(c.low()),
                        BigDecimal.valueOf(c.close()),
                        BigDecimal.valueOf(c.volume())
                ))
                .toList();
    }

    @Override
    public PricePoint getLastPrice(Long chatId, String symbol) {
        BigDecimal last = marketService.getCurrentPrice(chatId, symbol);
        return new PricePoint(System.currentTimeMillis(), last);
    }

    @Override
    public TrendInfo getTrendInfo(Long chatId, String symbol, String timeframe) {
        BigDecimal change = marketService.getChangePct(chatId, symbol, timeframe);
        boolean up = change.compareTo(BigDecimal.ZERO) >= 0;
        return new TrendInfo(up, change);
    }
}
