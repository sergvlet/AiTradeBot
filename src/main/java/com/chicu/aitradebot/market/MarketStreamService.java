package com.chicu.aitradebot.market;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketStreamService {

    private final MarketStreamManager streamManager;
    private final StrategyLivePublisher live;
    private final StrategyRegistry strategyRegistry;

    // =====================================================================
    // GLOBAL MARKET CACHE
    // =====================================================================
    public void onKline(UnifiedKline kline) {

        if (kline == null) return;
        if (kline.getSymbol() == null || kline.getTimeframe() == null) return;
        if (kline.getOpen() == null || kline.getHigh() == null
            || kline.getLow() == null || kline.getClose() == null) {
            return;
        }

        String symbol = kline.getSymbol().trim().toUpperCase(Locale.ROOT);
        String timeframe = kline.getTimeframe().trim().toLowerCase(Locale.ROOT);

        Candle candle = new Candle(
                kline.getOpenTime(),                 // время открытия свечи
                kline.getOpen().doubleValue(),
                kline.getHigh().doubleValue(),
                kline.getLow().doubleValue(),
                kline.getClose().doubleValue(),
                kline.getVolume() != null
                        ? kline.getVolume().doubleValue()
                        : 0.0,
                true
        );

        streamManager.addCandle(symbol, timeframe, candle);
    }

    // =====================================================================
    // STRATEGY PIPELINE v4 — ЖИВОЙ UI
    // =====================================================================
    public void onKline(long chatId, StrategyType strategyType, UnifiedKline kline) {

        if (kline == null || strategyType == null) return;

        // 1️⃣ ВСЕГДА сначала обновляем рынок
        onKline(kline);

        String symbol = kline.getSymbol().toUpperCase(Locale.ROOT);
        String timeframe = kline.getTimeframe().toLowerCase(Locale.ROOT);

        BigDecimal open  = kline.getOpen();
        BigDecimal high  = kline.getHigh();
        BigDecimal low   = kline.getLow();
        BigDecimal close = kline.getClose();
        BigDecimal vol   = kline.getVolume() != null
                ? kline.getVolume()
                : BigDecimal.ZERO;

        Instant candleTime = Instant.ofEpochMilli(kline.getOpenTime());

        // 2️⃣ UI — CANDLE (OHLC)
        live.pushCandleOhlc(
                chatId,
                strategyType,
                symbol,
                timeframe,
                open,
                high,
                low,
                close,
                vol,
                candleTime
        );

        // 3️⃣ UI — PRICE TICK (КРИТИЧНО ДЛЯ ЖИВОЙ ЛИНИИ ЦЕНЫ)
        live.pushPriceTick(
                chatId,
                strategyType,
                symbol,
                close,          // текущая цена
                candleTime
        );

        // 4️⃣ СТРАТЕГИЯ (ТОЛЬКО ЕСЛИ АКТИВНА)
        TradingStrategy strategy = strategyRegistry.get(strategyType);
        if (strategy == null || !strategy.isActive(chatId)) {
            return;
        }

        strategy.onPriceUpdate(
                chatId,
                symbol,
                close,
                candleTime
        );
    }
}
