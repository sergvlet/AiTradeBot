package com.chicu.aitradebot.market;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * InMemoryMarketPriceService
 *
 * Хранит последние цены в памяти (BigDecimal).
 * MarketStreamRouter пушит сюда обновления.
 * Стратегии читают отсюда актуальные цены.
 */
@Slf4j
@Service
public class InMemoryMarketPriceService implements MarketPriceService {

    private final ConcurrentMap<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();

    @Override
    public void updatePrice(String symbol, BigDecimal price) {
        if (symbol == null || symbol.isBlank() || price == null) {
            return;
        }
        lastPrices.put(normalize(symbol), price);
    }

    @Override
    public Optional<BigDecimal> getLastPrice(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(lastPrices.get(normalize(symbol)));
    }

    private String normalize(String s) {
        return s.trim().toUpperCase();
    }
}
