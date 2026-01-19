package com.chicu.aitradebot.market;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.common.util.TimeframeUtils;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyRegistry;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketStreamService {

    private final MarketStreamManager streamManager;
    private final StrategyLivePublisher live;
    private final StrategyRegistry strategyRegistry;

    @Getter
    private final ExchangeClientFactory exchangeClientFactory;
    private final Map<String, Long> lastAggDispatchAt = new ConcurrentHashMap<>();
    private static final long DISPATCH_EVERY_MS = 250; // 100–500мс нормально для скальпинга


    private final ObjectMapper objectMapper;

    /**
     * key = symbol|tf → last push millis
     * (оставлено на будущее, сейчас не используется)
     */
    @Getter
    private final Map<String, Long> lastLiveCandlePushAt = new ConcurrentHashMap<>();

    // =====================================================================
    // THROTTLED LOGS (anti-spam)
    // =====================================================================
    private static final long AGG_LOG_EVERY = 200;
    private static final AtomicLong AGG_COUNTER = new AtomicLong(0);



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
        try {
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
        } catch (Exception e) {
            log.warn("❗ pushCandleOhlc (kline) failed chatId={} type={} {} {}: {}",
                    chatId, strategyType, symbol, timeframe, e.getMessage());
        }

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
    // AGG TRADE — PRICE + CANDLE + THROTTLED LOG
    // =====================================================================

    public void onAggTrade(
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            String rawJson
    ) {
        boolean pushedTick = false;
        boolean pushedCandle = false;
        boolean createdCandle = false;
        boolean dispatched = false;

        BigDecimal priceBd = null;
        BigDecimal qtyBd = BigDecimal.ZERO;
        Instant tickTs = null;

        final String sym = symbol == null ? null : symbol.trim().toUpperCase(Locale.ROOT);
        final String tf  = timeframe == null ? null : timeframe.trim().toLowerCase(Locale.ROOT);

        try {
            if (sym == null || sym.isBlank() || tf == null || tf.isBlank()) return;
            if (rawJson == null || rawJson.isBlank()) return;

            JsonNode root;
            try {
                root = objectMapper.readTree(rawJson);
            } catch (Exception ignore) {
                return;
            }

            // Binance combined streams: { "stream": "...", "data": {...} }
            JsonNode data = root.has("data") ? root.get("data") : root;
            if (data == null || !data.hasNonNull("p")) return;

            // price
            try {
                priceBd = new BigDecimal(data.get("p").asText());
            } catch (Exception ignore) {
                return;
            }
            if (priceBd.signum() <= 0) return;

            // qty
            if (data.hasNonNull("q")) {
                try {
                    qtyBd = new BigDecimal(data.get("q").asText());
                    if (qtyBd.signum() < 0) qtyBd = BigDecimal.ZERO;
                } catch (Exception ignore) {
                    qtyBd = BigDecimal.ZERO;
                }
            }

            // ts
            tickTs = data.has("T")
                    ? Instant.ofEpochMilli(data.get("T").asLong())
                    : Instant.now();

            // =========================================================
            // 1) UI: push tick
            // =========================================================
            try {
                live.pushPriceTick(chatId, strategyType, sym, tf, priceBd, tickTs);
                pushedTick = true;
            } catch (Exception e) {
                log.warn("❗ pushPriceTick failed chatId={} type={} {} {}: {}",
                        chatId, strategyType, sym, tf, e.getMessage());
            }

            // =========================================================
            // 2) Cache: update/create current candle (optional for UI snapshot)
            // =========================================================
            double price = priceBd.doubleValue();
            if (price <= 0 || Double.isNaN(price) || Double.isInfinite(price)) return;

            Candle c;
            List<Candle> candles = streamManager.getCandles(sym, tf, 1);

            if (candles == null || candles.isEmpty()) {
                long tfMs;
                try {
                    tfMs = TimeframeUtils.toMillis(tf);
                } catch (Exception ignore) {
                    tfMs = 0;
                }

                long tickMs = tickTs.toEpochMilli();
                long openTime = (tfMs > 0) ? (tickMs / tfMs) * tfMs : tickMs;

                double vol = (qtyBd.signum() > 0) ? qtyBd.doubleValue() : 0.0;

                c = new Candle(openTime, price, price, price, price, vol, false);
                streamManager.addCandle(sym, tf, c);
                createdCandle = true;
            } else {
                c = candles.getFirst();
                c.setClose(price);
                c.setHigh(Math.max(c.getHigh(), price));
                c.setLow(Math.min(c.getLow(), price));
                if (qtyBd.signum() > 0) c.setVolume(c.getVolume() + qtyBd.doubleValue());
            }

            // =========================================================
            // 3) UI: push candle (current)
            // =========================================================
            try {
                live.pushCandleOhlc(
                        chatId,
                        strategyType,
                        sym,
                        tf,
                        BigDecimal.valueOf(c.getOpen()),
                        BigDecimal.valueOf(c.getHigh()),
                        BigDecimal.valueOf(c.getLow()),
                        BigDecimal.valueOf(c.getClose()),
                        BigDecimal.valueOf(c.getVolume()),
                        Instant.ofEpochMilli(c.getTime())
                );
                pushedCandle = true;
            } catch (Exception e) {
                log.warn("❗ pushCandleOhlc (aggTrade) failed chatId={} type={} {} {}: {}",
                        chatId, strategyType, sym, tf, e.getMessage());
            }

            // =========================================================
            // 4) ✅ DISPATCH: strategy.onPriceUpdate (это и есть “торговля”)
            // =========================================================
            try {
                TradingStrategy strategy = strategyRegistry.get(strategyType);
                if (strategy != null && strategy.isActive(chatId)) {
                    strategy.onPriceUpdate(chatId, sym, priceBd, tickTs);
                    dispatched = true;
                }
            } catch (Exception e) {
                log.warn("❗ dispatch onPriceUpdate failed chatId={} type={} {} {}: {}",
                        chatId, strategyType, sym, tf, e.getMessage());
            }

        } catch (Exception e) {
            log.warn("aggTrade processing failed", e);

        } finally {
            long n = AGG_COUNTER.incrementAndGet();
            if (n % AGG_LOG_EVERY == 0) {
                log.info("📈 AGG_TICK[{}] chatId={} type={} {} {} price={} qty={} ts={} pushedTick={} pushedCandle={} createdCandle={} dispatched={}",
                        n,
                        chatId,
                        strategyType,
                        sym,
                        tf,
                        (priceBd != null ? priceBd.toPlainString() : "null"),
                        qtyBd.toPlainString(),
                        (tickTs != null ? tickTs.toEpochMilli() : -1),
                        pushedTick,
                        pushedCandle,
                        createdCandle,
                        dispatched
                );
            }
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
