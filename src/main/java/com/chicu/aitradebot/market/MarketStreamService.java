package com.chicu.aitradebot.market;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
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

    /** Глобальный рынок: кэшируем свечу */
    public void onKline(UnifiedKline kline) {
        if (kline == null) {
            log.warn("onKline: null kline (global market stream)");
            return;
        }

        String symbol = kline.getSymbol() != null ? kline.getSymbol().toUpperCase(Locale.ROOT) : "";
        String timeframe = kline.getTimeframe() != null ? kline.getTimeframe().toLowerCase(Locale.ROOT) : "";

        if (symbol.isEmpty() || timeframe.isEmpty()) {
            log.warn("onKline: пустой symbol/timeframe для kline={}", kline);
            return;
        }

        double volume = (kline.getVolume() != null) ? kline.getVolume().doubleValue() : 0.0;

        // ❗ UnifiedKline.isClosed() у тебя нет — НЕ трогаем его
        Candle candle = new Candle(
                kline.getOpenTime(),
                kline.getOpen().doubleValue(),
                kline.getHigh().doubleValue(),
                kline.getLow().doubleValue(),
                kline.getClose().doubleValue(),
                volume,
                true // считаем закрытой/валидной для кэша
        );

        streamManager.addCandle(symbol, timeframe, candle);

        log.trace("📦 cached candle {} {} [{}]",
                symbol, timeframe, Instant.ofEpochMilli(kline.getOpenTime()));
    }

    /**
     * ✅ ВОТ ОНО: “стратегический” вход.
     * Здесь мы и оживляем график: пушим candle в /topic/strategy/{chatId}/{strategyType}
     */
    public void onKline(long chatId, StrategyType strategyType, UnifiedKline kline) {
        onKline(kline);

        if (kline == null || strategyType == null) return;

        String symbol = kline.getSymbol() != null ? kline.getSymbol().toUpperCase(Locale.ROOT) : "";
        String timeframe = kline.getTimeframe() != null ? kline.getTimeframe().toLowerCase(Locale.ROOT) : "";
        if (symbol.isEmpty() || timeframe.isEmpty()) return;

        BigDecimal o = kline.getOpen();
        BigDecimal h = kline.getHigh();
        BigDecimal l = kline.getLow();
        BigDecimal c = kline.getClose();
        BigDecimal v = kline.getVolume() != null ? kline.getVolume() : BigDecimal.ZERO;

        // 🔥 живые свечи в UI
        live.pushCandleOhlc(
                chatId,
                strategyType,
                symbol,
                timeframe,
                o, h, l, c, v,
                Instant.ofEpochMilli(kline.getOpenTime())
        );
    }
}
