package com.chicu.aitradebot.market.stream;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.binance.ws.BinanceSpotWebSocketClient;
import com.chicu.aitradebot.market.model.Candle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataStreamService {

    private static final int MAX_CANDLES = 2_000;

    private final BinanceSpotWebSocketClient binanceSpotWebSocketClient;

    /**
     * 🧠 ХРАНИЛИЩЕ СВЕЧЕЙ
     *
     * chatId
     *   → strategy
     *     → symbol
     *       → timeframe
     *         → candles
     */
    private final Map<Long, Map<StrategyType, Map<String, Map<String, List<Candle>>>>>
            candleStorage = new ConcurrentHashMap<>();

    /**
     * Активные подписки:
     * chatId → set of keys
     */
    private final Map<Long, Set<SubscriptionKey>> activeSubscriptions =
            new ConcurrentHashMap<>();

    // =====================================================================
    // 🕯 + 🔥 Подписка на свечи и live ticks
    // =====================================================================
    public synchronized void subscribeCandles(
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe
    ) {

        if (symbol == null || timeframe == null || strategyType == null) {
            log.warn(
                    "⚠️ subscribeCandles skipped (invalid args): chatId={} type={} symbol={} tf={}",
                    chatId, strategyType, symbol, timeframe
            );
            return;
        }

        String sym = symbol.trim().toUpperCase();
        String tf  = timeframe.trim().toLowerCase();

        SubscriptionKey key = new SubscriptionKey(strategyType, sym, tf);

        // =====================================================
        // 🟢 0️⃣ ГАРАНТИРУЕМ ИНИЦИАЛИЗАЦИЮ CACHE
        // =====================================================
        candleStorage
                .computeIfAbsent(chatId, __ -> new ConcurrentHashMap<>())
                .computeIfAbsent(strategyType, __ -> new ConcurrentHashMap<>())
                .computeIfAbsent(sym, __ -> new ConcurrentHashMap<>())
                .computeIfAbsent(tf, __ -> new CopyOnWriteArrayList<>());

        Set<SubscriptionKey> subs =
                activeSubscriptions.computeIfAbsent(
                        chatId,
                        k -> ConcurrentHashMap.newKeySet()
                );

        if (subs.contains(key)) {
            log.debug(
                    "⏭ Already subscribed: {} {} {} (chatId={})",
                    strategyType, sym, tf, chatId
            );
            return;
        }

        // =====================================================
        // 🔥 1️⃣ KLINE — закрытие свечей
        // =====================================================
        binanceSpotWebSocketClient.subscribeKline(
                sym.toLowerCase(),
                tf,
                chatId,
                strategyType
        );

        // =====================================================
        // 🔥 2️⃣ AGG TRADE — LIVE PRICE
        // =====================================================
        binanceSpotWebSocketClient.subscribeAggTrade(
                sym.toLowerCase(),
                tf,
                chatId,
                strategyType
        );

        subs.add(key);

        log.info(
                "📡 SUBSCRIBE Binance STREAMS (KLINE + AGGTRADE): {} {} (chatId={}, strategy={})",
                sym, tf, chatId, strategyType
        );
    }

    // =====================================================================
    // 🕯 CALLBACK ДЛЯ LIVE СВЕЧЕЙ (вызывается из WS клиента)
    // =====================================================================
    public void onCandle(
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            Candle candle
    ) {

        List<Candle> candles = candleStorage
                .computeIfAbsent(chatId, __ -> new ConcurrentHashMap<>())
                .computeIfAbsent(strategyType, __ -> new ConcurrentHashMap<>())
                .computeIfAbsent(symbol, __ -> new ConcurrentHashMap<>())
                .computeIfAbsent(timeframe, __ -> new CopyOnWriteArrayList<>());

        candles.add(candle);

        if (candles.size() > MAX_CANDLES) {
            candles.remove(0);
        }

        log.debug(
                "🕯 CANDLE IN {} {} {} time={}",
                strategyType, symbol, timeframe, candle.getOpen()
        );
    }

    // =====================================================================
    // 📊 SNAPSHOT ДЛЯ ГРАФИКА — НИКОГДА НЕ NULL
    // =====================================================================
    public List<Candle> getCandles(
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe
    ) {

        return candleStorage
                .getOrDefault(chatId, Map.of())
                .getOrDefault(strategyType, Map.of())
                .getOrDefault(symbol, Map.of())
                .getOrDefault(timeframe, List.of());
    }

    // =====================================================================
    // 📦 PRELOAD (используется WebChartFacade)
    // =====================================================================
    public void putCandles(
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            List<Candle> candles
    ) {

        List<Candle> target = candleStorage
                .computeIfAbsent(chatId, __ -> new ConcurrentHashMap<>())
                .computeIfAbsent(strategyType, __ -> new ConcurrentHashMap<>())
                .computeIfAbsent(symbol, __ -> new ConcurrentHashMap<>())
                .computeIfAbsent(timeframe, __ -> new CopyOnWriteArrayList<>());

        target.clear();
        target.addAll(candles);

        log.info(
                "📦 Cache initialized {} candles for {} {} {}",
                candles.size(), strategyType, symbol, timeframe
        );
    }

    // =====================================================================
    // 🧹 Отписка
    // =====================================================================
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

        candleStorage.remove(chatId);

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
