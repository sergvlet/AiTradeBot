package com.chicu.aitradebot.market.stream;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.binance.ws.BinanceSpotWebSocketClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataStreamService {

    private final BinanceSpotWebSocketClient binanceSpotWebSocketClient;

    /**
     * Активные подписки:
     * chatId → set of keys
     */
    private final Map<Long, Set<SubscriptionKey>> activeSubscriptions = new ConcurrentHashMap<>();

    /**
     * Подписка на свечи (V4-safe)
     */
    public synchronized void subscribeCandles(long chatId,
                                              StrategyType strategyType,
                                              String symbol,
                                              String timeframe) {

        String sym = symbol.toUpperCase();
        String tf  = timeframe.toLowerCase();

        SubscriptionKey key = new SubscriptionKey(strategyType, sym, tf);

        Set<SubscriptionKey> subs =
                activeSubscriptions.computeIfAbsent(chatId, k -> ConcurrentHashMap.newKeySet());

        if (subs.contains(key)) {
            log.debug("⏭ Already subscribed: {} {} {} (chatId={})",
                    strategyType, sym, tf, chatId);
            return;
        }

        // 👉 WS subscribe
        binanceSpotWebSocketClient.subscribeKline(
                sym.toLowerCase(),
                tf,
                chatId,
                strategyType
        );

        subs.add(key);

        log.info("📡 SUBSCRIBE Binance KLINE: {} {} (chatId={}, strategy={})",
                sym, tf, chatId, strategyType);
    }

    /**
     * Отписка (на будущее — понадобится)
     */
    public synchronized void unsubscribeAll(long chatId) {

        Set<SubscriptionKey> subs = activeSubscriptions.remove(chatId);
        if (subs == null || subs.isEmpty()) return;

        for (SubscriptionKey key : subs) {
            binanceSpotWebSocketClient.unsubscribeKline(
                    key.symbol().toLowerCase(),
                    key.timeframe(),
                    chatId,
                    key.strategyType()
            );
        }

        log.info("🧹 UNSUBSCRIBE ALL for chatId={}", chatId);
    }

    /**
     * Ключ подписки
     */
    private record SubscriptionKey(
            StrategyType strategyType,
            String symbol,
            String timeframe
    ) {}
}
