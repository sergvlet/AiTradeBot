package com.chicu.aitradebot.market;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.common.util.TimeframeUtils;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
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


    private final MarketStreamManager streamManager;
    private final StrategyLivePublisher live;
    private final StrategyRegistry strategyRegistry;
    @Getter
    private final ExchangeClientFactory exchangeClientFactory;
    private final ObjectMapper objectMapper;

    /**
     * key = symbol|tf → last push millis
     * (оставлено на будущее, сейчас не используется)
     */
    @Getter
    private final Map<String, Long> lastLiveCandlePushAt = new ConcurrentHashMap<>();

    // =====================================================================
    // LIVE KLINE — CACHE + UI
    // =====================================================================
    public void onKline(
            long chatId,
            StrategyType strategyType,
            UnifiedKline kline
    ) {
        if (kline == null) return;

        String symbol = kline.getSymbol().toUpperCase(Locale.ROOT);
        String timeframe = kline.getTimeframe().toLowerCase(Locale.ROOT);

        Candle candle = new Candle(
                kline.getOpenTime(),
                kline.getOpen().doubleValue(),
                kline.getHigh().doubleValue(),
                kline.getLow().doubleValue(),
                kline.getClose().doubleValue(),
                kline.getVolume().doubleValue(),
                kline.isClosed()
        );

        // 1️⃣ всегда обновляем кеш
        streamManager.addCandle(symbol, timeframe, candle);

        // 2️⃣ публикуем candle в UI (каждое обновление текущей свечи)
        live.pushCandleOhlc(
                chatId,
                strategyType,
                symbol,
                timeframe,
                kline.getOpen(),
                kline.getHigh(),
                kline.getLow(),
                kline.getClose(),
                kline.getVolume(),
                Instant.ofEpochMilli(kline.getOpenTime())
        );

        // 3️⃣ в стратегию — ТОЛЬКО если свеча закрыта
        if (!kline.isClosed()) return;

        TradingStrategy strategy = strategyRegistry.get(strategyType);
        if (strategy != null && strategy.isActive(chatId)) {
            strategy.onPriceUpdate(
                    chatId,
                    symbol,
                    kline.getClose(),
                    Instant.ofEpochMilli(kline.getCloseTime())
            );
        }
    }

    // =====================================================================
    // AGG TRADE — PRICE + CANDLE
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

            Candle c = candles.getFirst();

            c.setClose(price);
            c.setHigh(Math.max(c.getHigh(), price));
            c.setLow(Math.min(c.getLow(), price));

            if (json.has("q")) {
                double qty = json.get("q").asDouble();
                if (qty > 0) {
                    c.setVolume(c.getVolume() + qty);
                }
            }

            // 🔥 live price (тик)
            live.pushPriceTick(
                    chatId,
                    strategyType,
                    symbol,
                    timeframe,
                    BigDecimal.valueOf(price),
                    tickTs
            );

            // 🔥 live candle (обновление текущей свечи)
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
                    Instant.ofEpochMilli(c.getTime())
            );

        } catch (Exception e) {
            // ❗ реальная ошибка — логируем
            log.warn("aggTrade processing failed: {}", e.getMessage());
        }
    }

    // =====================================================================
    // CLOSE CANDLE — РЕДКОЕ И ПОЛЕЗНОЕ СОБЫТИЕ
    // =====================================================================
    public void closeCandle(
            long ignoredChatId,
            UnifiedKline kline
    ) {
        String symbol = kline.getSymbol().toUpperCase();
        String timeframe = kline.getTimeframe().toLowerCase();

        List<Candle> candles = streamManager.getCandles(symbol, timeframe, 1);
        if (candles.isEmpty()) return;

        Candle last = candles.getFirst();
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

        // ✅ ЭТОТ ЛОГ ОСТАВЛЯЕМ
        log.info("🕯 Candle closed {} {} @{}", symbol, timeframe, nextOpenTime);
    }

    // =====================================================================
    // LEGACY ENTRY — ДЛЯ ADAPTER / HISTORY / REPLAY
    // =====================================================================
    public void onKline(UnifiedKline kline) {
        if (kline == null) return;

        String symbol = kline.getSymbol().toUpperCase(Locale.ROOT);
        String timeframe = kline.getTimeframe().toLowerCase(Locale.ROOT);

        Candle candle = new Candle(
                kline.getOpenTime(),
                kline.getOpen().doubleValue(),
                kline.getHigh().doubleValue(),
                kline.getLow().doubleValue(),
                kline.getClose().doubleValue(),
                kline.getVolume().doubleValue(),
                kline.isClosed()
        );

        // ⚠️ только кеш, без UI и WS
        streamManager.addCandle(symbol, timeframe, candle);
    }

}
