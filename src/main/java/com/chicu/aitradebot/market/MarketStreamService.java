package com.chicu.aitradebot.market;

import com.chicu.aitradebot.common.enums.StrategyType;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketStreamService {

    private static final int INITIAL_HISTORY_LIMIT = 1000;

    private final MarketStreamManager streamManager;
    private final StrategyLivePublisher live;
    private final StrategyRegistry strategyRegistry;
    private final ExchangeClientFactory exchangeClientFactory;
    private final ObjectMapper objectMapper;


    // =====================================================================
    // GLOBAL KLINE UPDATE (НЕ СОЗДАЁТ СВЕЧУ!)
    // =====================================================================
    public void onKline(UnifiedKline kline) {
        if (kline == null) return;

        String symbol = kline.getSymbol().toUpperCase(Locale.ROOT);
        String timeframe = kline.getTimeframe().toLowerCase(Locale.ROOT);

        List<Candle> candles = streamManager.getCandles(symbol, timeframe, 1);
        if (candles.isEmpty()) return;

        Candle c = candles.get(0);

        c.setOpen(kline.getOpen().doubleValue());
        c.setHigh(kline.getHigh().doubleValue());
        c.setLow(kline.getLow().doubleValue());
        c.setClose(kline.getClose().doubleValue());

        if (kline.getVolume() != null) {
            c.setVolume(kline.getVolume().doubleValue());
        }
    }

    // =====================================================================
    // STRATEGY PIPELINE
    // =====================================================================
    public void onKline(long chatId, StrategyType strategyType, UnifiedKline kline) {

        preloadHistoryIfNeeded(chatId,
                kline.getSymbol().toUpperCase(),
                kline.getTimeframe().toLowerCase()
        );

        onKline(kline);

        String symbol = kline.getSymbol().toUpperCase();
        String timeframe = kline.getTimeframe().toLowerCase();
        Instant ts = Instant.ofEpochMilli(kline.getOpenTime());

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
                ts
        );

        live.pushPriceTick(chatId, strategyType, symbol, kline.getClose(), ts);

        TradingStrategy strategy = strategyRegistry.get(strategyType);
        if (strategy != null && strategy.isActive(chatId)) {
            strategy.onPriceUpdate(chatId, symbol, kline.getClose(), ts);
        }
    }

    // =====================================================================
    // REST HISTORY PRELOAD
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
// 🔥 LIVE TICK (aggTrade) — С СИНХРОНИЗАЦИЕЙ ВРЕМЕНИ
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

            // цена обязательна
            if (!json.has("p")) return;

            double price = json.get("p").asDouble();
            if (Double.isNaN(price) || price <= 0) return;

            // 🔥 РЕАЛЬНОЕ ВРЕМЯ ТИКА (Binance aggTrade.T)
            Instant tickTs = json.has("T")
                    ? Instant.ofEpochMilli(json.get("T").asLong())
                    : Instant.now();

            symbol = symbol.toUpperCase();
            timeframe = timeframe.toLowerCase();

            List<Candle> candles = streamManager.getCandles(symbol, timeframe, 1);
            if (candles.isEmpty()) return;

            Candle c = candles.get(0);

            // =====================================================
            // 🕯 ОБНОВЛЯЕМ ТЕКУЩУЮ СВЕЧУ (OHLC)
            // =====================================================
            c.setClose(price);
            c.setHigh(Math.max(c.getHigh(), price));
            c.setLow(Math.min(c.getLow(), price));

            if (json.has("q")) {
                double qty = json.get("q").asDouble();
                if (!Double.isNaN(qty) && qty > 0) {
                    c.setVolume(c.getVolume() + qty);
                }
            }

            // ❗ ВРЕМЯ СВЕЧИ = openTime
            Instant candleTs = Instant.ofEpochMilli(c.getTime());

            // =====================================================
            // 📤 UI: CANDLE (обновляем последнюю свечу)
            // =====================================================
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
                    candleTs
            );

            // =====================================================
            // 📤 UI: PRICE (НАСТОЯЩИЙ LIVE TICK)
            // =====================================================
            live.pushPriceTick(
                    chatId,
                    strategyType,
                    symbol,
                    BigDecimal.valueOf(price),
                    tickTs   // 🔥 ВАЖНО: ВРЕМЯ ТИКА, НЕ СВЕЧИ
            );

            log.debug("🔥 LIVE TICK {} {} price={}", symbol, timeframe, price);

        } catch (Exception e) {
            log.debug("aggTrade skipped: {}", e.getMessage());
        }
    }




    // =====================================================================
    // 🔒 CLOSE CANDLE (kline.x = true)
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

        long nextOpenTime = kline.getCloseTime() + 1;
        double p = kline.getClose().doubleValue();

        Candle next = new Candle(
                nextOpenTime,
                p, p, p, p,
                0.0,
                false
        );

        streamManager.addCandle(symbol, timeframe, next);

        log.debug("🕯 CLOSED & OPENED {} {}", symbol, timeframe);
    }
}
