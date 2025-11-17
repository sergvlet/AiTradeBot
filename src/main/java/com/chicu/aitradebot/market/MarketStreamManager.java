package com.chicu.aitradebot.market;

import com.chicu.aitradebot.market.ws.CandleWebSocketHandler;
import com.chicu.aitradebot.market.ws.TradeWebSocketHandler;
import com.chicu.aitradebot.market.ws.TradeFeedListener;
import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionCandleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Центральный менеджер трейдов / тиков.
 * Получает реальные сделки из BinancePublicTradeStreamService
 * и отправляет их в:
 *   • SmartFusionCandleService (live-свечи)
 *   • TradeWebSocketHandler (реальный поток сделок)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketStreamManager implements TradeFeedListener {

    private final SmartFusionCandleService candleService;
    private final CandleWebSocketHandler candleHandler;
    private final TradeWebSocketHandler tradeHandler;

    /** symbol → last price */
    private final Map<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();

    /** активные подписки */
    private final Map<String, Boolean> active = new ConcurrentHashMap<>();

    // ==========================================================================
    // REAL TRADE FROM BINANCE
    // ==========================================================================

    @Override
    public void onTrade(String symbol, BigDecimal price, long ts) {
        if (symbol == null || price == null) return;

        String sym = symbol.toUpperCase(Locale.ROOT);
        lastPrices.put(sym, price);

        if (!active.containsKey(sym)) return;

        // ⚡ Живые свечи (1s/1m/5m/15m/1h…) пушатся из SmartFusionCandleService
        candleService.onTradeTick(sym, ts, price.doubleValue());
    }

    // ==========================================================================
    // MANUAL TRADE PUSH (из стратегий)
    // ==========================================================================

    public void pushTrade(String symbol, double price, double qty, String side) {
        if (symbol == null || side == null) return;

        String sym = symbol.toUpperCase(Locale.ROOT);

        Map<String, Object> data = new HashMap<>();
        data.put("symbol", sym);
        data.put("price", price);
        data.put("qty", qty);
        data.put("side", side);
        data.put("ts", System.currentTimeMillis());

        try {
            tradeHandler.broadcastTrade(
                    System.currentTimeMillis(),
                    sym,
                    data
            );

            log.info("📤 PUSH TRADE: {} {} qty={} price={}", side, sym, qty, price);

        } catch (Exception e) {
            log.error("❌ Ошибка pushTrade WS: {}", e.getMessage());
        }
    }

    // ==========================================================================
    // SUBSCRIPTIONS
    // ==========================================================================

    public void subscribeSymbol(String symbol) {
        if (symbol == null) return;
        String sym = symbol.toUpperCase(Locale.ROOT);
        active.put(sym, true);
        log.info("📡 MarketStreamManager: подписка активирована для {}", sym);
    }

    public void unsubscribeSymbol(String symbol) {
        if (symbol == null) return;
        String sym = symbol.toUpperCase(Locale.ROOT);
        active.remove(sym);
        log.info("📡 MarketStreamManager: подписка снята для {}", sym);
    }

    public BigDecimal getLastPrice(String symbol) {
        if (symbol == null) return BigDecimal.ZERO;
        return lastPrices.getOrDefault(symbol.toUpperCase(Locale.ROOT), BigDecimal.ZERO);
    }
}
