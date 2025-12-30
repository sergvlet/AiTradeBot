package com.chicu.aitradebot.market;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.common.util.TimeframeUtils;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketStreamService {

    private static final int INITIAL_HISTORY_LIMIT = 1000;
    private static final long LIVE_CANDLE_THROTTLE_MS = 500;

    private final MarketStreamManager streamManager;
    private final StrategyLivePublisher live;
    private final StrategyRegistry strategyRegistry;
    private final ExchangeClientFactory exchangeClientFactory;
    private final ObjectMapper objectMapper;

    /**
     * key = chatId|strategy|symbol|tf → last push millis
     */
    private final Map<String, Long> lastLiveCandlePushAt = new ConcurrentHashMap<>();

    // =====================================================================
    // ❌ KLINE НЕ ОБНОВЛЯЕТ OHLC
    // =====================================================================
    public void onKline(UnifiedKline kline) {
        // намеренно ПУСТО
        // kline используется ТОЛЬКО в closeCandle()
    }

    // =====================================================================
    // STRATEGY PIPELINE
    // =====================================================================
    public void onKline(long chatId, StrategyType strategyType, UnifiedKline kline) {

        preloadHistoryIfNeeded(
                chatId,
                kline.getSymbol().toUpperCase(),
                kline.getTimeframe().toLowerCase()
        );

        // ❗ НИКАКИХ UI И OHLC ТУТ НЕТ

        TradingStrategy strategy = strategyRegistry.get(strategyType);
        if (strategy != null && strategy.isActive(chatId)) {
            strategy.onPriceUpdate(
                    chatId,
                    kline.getSymbol().toUpperCase(),
                    kline.getClose(),
                    Instant.ofEpochMilli(kline.getCloseTime())
            );
        }
    }

    // =====================================================================
    // PRELOAD HISTORY
    // =====================================================================
    private void preloadHistoryIfNeeded(long chatId, String symbol, String timeframe) {

        if (!streamManager.getCandles(symbol, timeframe, 1).isEmpty()) {
            return;
        }

        try {
            ExchangeClient client = exchangeClientFactory.getByChat(chatId);
            List<ExchangeClient.Kline> klines =
                    client.getKlines(symbol, timeframe, INITIAL_HISTORY_LIMIT);

            for (ExchangeClient.Kline k : klines) {
                streamManager.addCandle(
                        symbol,
                        timeframe,
                        new Candle(
                                k.openTime(),
                                k.open(),
                                k.high(),
                                k.low(),
                                k.close(),
                                k.volume(),
                                true
                        )
                );
            }

            log.info("📥 Preloaded {} candles {} {}", klines.size(), symbol, timeframe);

        } catch (Exception e) {
            log.error("❌ Preload failed {} {}", symbol, timeframe, e);
        }
    }

    // =====================================================================
    // 🔥 AGG TRADE — ЕДИНСТВЕННЫЙ ИСТОЧНИК LIVE OHLC
    // =====================================================================
    public void onAggTrade(
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            String rawJson
    ) {
        try {
            var json = objectMapper.readTree(rawJson);
            if (!json.has("p")) return;

            double price = json.get("p").asDouble();
            if (price <= 0 || Double.isNaN(price)) return;

            Instant tickTs = json.has("T")
                    ? Instant.ofEpochMilli(json.get("T").asLong())
                    : Instant.now();

            symbol = symbol.toUpperCase(Locale.ROOT);
            timeframe = timeframe.toLowerCase(Locale.ROOT);

            List<Candle> candles = streamManager.getCandles(symbol, timeframe, 1);

            if (candles.isEmpty()) return;

            Candle c = candles.get(0);

            // 🕯 LIVE UPDATE
            c.setClose(price);
            c.setHigh(Math.max(c.getHigh(), price));
            c.setLow(Math.min(c.getLow(), price));

            if (json.has("q")) {
                double qty = json.get("q").asDouble();
                if (qty > 0) {
                    c.setVolume(c.getVolume() + qty);
                }
            }

            // ✅ UI: PRICE (каждый тик)
            live.pushPriceTick(
                    chatId,
                    strategyType,
                    symbol,
                    BigDecimal.valueOf(price),
                    tickTs
            );

            // 🟡 UI: CANDLE (throttled, БЕЗ смены времени)
            String key = chatId + "|" + strategyType + "|" + symbol + "|" + timeframe;
            long now = System.currentTimeMillis();
            Long prev = lastLiveCandlePushAt.get(key);

            if (prev == null || now - prev >= LIVE_CANDLE_THROTTLE_MS) {
                lastLiveCandlePushAt.put(key, now);

                live.pushCandleOhlc(
                        chatId,
                        strategyType,
                        symbol,
                        timeframe,
                        BigDecimal.valueOf(c.getOpen()),
                        BigDecimal.valueOf(c.getHigh()),
                        BigDecimal.valueOf(c.getLow()),
                        BigDecimal.valueOf(c.getClose()),
                        BigDecimal.valueOf(c.getVolume()),
                        Instant.ofEpochMilli(c.getTime()) // ❗ время свечи
                );
            }

        } catch (Exception e) {
            log.debug("aggTrade skipped: {}", e.getMessage());
        }
    }

    // =====================================================================
    // 🔒 CLOSE CANDLE — ЕДИНСТВЕННОЕ МЕСТО ЗАКРЫТИЯ
    // =====================================================================
    public void closeCandle(
            long chatId,
            StrategyType strategyType,
            UnifiedKline kline
    ) {
        String symbol = kline.getSymbol().toUpperCase();
        String timeframe = kline.getTimeframe().toLowerCase();

        List<Candle> candles = streamManager.getCandles(symbol, timeframe, 1);
        if (candles.isEmpty()) return;

        Candle last = candles.get(0);
        last.setClosed(true);

        long tfMs = TimeframeUtils.toMillis(timeframe);
        long nextOpenTime = kline.getOpenTime() + tfMs;
        double p = kline.getClose().doubleValue();

        Candle next = new Candle(
                nextOpenTime,
                p, p, p, p,
                0.0,
                false
        );

        streamManager.addCandle(symbol, timeframe, next);

        log.debug("🕯 CLOSED & OPENED {} {} @{}", symbol, timeframe, nextOpenTime);
    }
}

