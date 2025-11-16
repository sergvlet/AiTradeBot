package com.chicu.aitradebot.market.ws;

import com.chicu.aitradebot.market.TradeCacheService;
import com.chicu.aitradebot.market.model.TradeTick;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 🔔 Получает тики от биржевых WebSocket клиентов и сохраняет в кэш.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeFeedListener {

    private final TradeCacheService cache;

    public void onTrade(String symbol, long tsMillis, double price, double qty, boolean isBuy) {
        cache.put(new TradeTick(symbol, Instant.ofEpochMilli(tsMillis), price, qty, isBuy));
    }
}
