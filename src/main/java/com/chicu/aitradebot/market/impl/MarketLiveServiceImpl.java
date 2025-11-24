package com.chicu.aitradebot.market.impl;

import com.chicu.aitradebot.market.MarketLiveService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class MarketLiveServiceImpl implements MarketLiveService {

    // key = SYMBOL (например "BTCUSDT")
    private final Map<String, PricePoint> prices = new ConcurrentHashMap<>();

    @Override
    public void subscribe(String symbol, String timeframe) {
        // тут можно будет дергать WS-слой, если нужно
        log.info("🌐 MarketLiveService: subscribe symbol={}, timeframe={}", symbol, timeframe);
    }

    @Override
    public void updatePrice(String symbol, BigDecimal price) {
        long now = System.currentTimeMillis();
        prices.put(symbol, new PricePoint(now, price));
        log.debug("💹 MarketLiveService: tick {} -> {}", symbol, price);
    }

    @Override
    public PricePoint getLastPrice(String symbol) {
        PricePoint p = prices.get(symbol);
        if (p != null) {
            return p;
        }
        // если ещё не было ни одного тика — вернём 0
        return new PricePoint(System.currentTimeMillis(), BigDecimal.ZERO);
    }
}
