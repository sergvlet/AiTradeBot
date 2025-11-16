package com.chicu.aitradebot.market;

import com.chicu.aitradebot.market.ws.TradeFeedListener;
import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionCandleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Центральный менеджер потока сделок.
 * Сюда приходят трейды от BinancePublicTradeStreamService.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketStreamManager implements TradeFeedListener {

    private final SmartFusionCandleService candleService;

    /** symbol -> lastPrice */
    private final Map<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();

    /** Подписанные пары */
    private final Map<String, Boolean> active = new ConcurrentHashMap<>();

    @Override
    public void onTrade(String symbol, BigDecimal price, long ts) {
        lastPrices.put(symbol, price);

        if (!active.containsKey(symbol)) return;

        // ⚡ Исправлено — порядок аргументов правильный
        candleService.onTradeTick(symbol, ts, price.doubleValue());
    }

    public void subscribeSymbol(String symbol) {
        active.put(symbol, true);
        log.info("📡 MarketStreamManager: отслеживаем {}", symbol);
    }

    public void unsubscribeSymbol(String symbol) {
        active.remove(symbol);
        log.info("📡 MarketStreamManager: снят {}", symbol);
    }

    public BigDecimal getLastPrice(String symbol) {
        return lastPrices.get(symbol);
    }
}
