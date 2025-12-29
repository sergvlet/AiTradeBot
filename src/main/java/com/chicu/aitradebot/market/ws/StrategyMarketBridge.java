package com.chicu.aitradebot.market.ws;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyMarketBridge {

    private final CandleProvider candleProvider;
    private final StrategyLivePublisher livePublisher;
    private final StrategyRegistry strategyRegistry; // ✅ ДОБАВЛЕНО

    /**
     * 🔥 LIVE-СВЕЧА
     * ⚠️ Принимаем любую, но пишем и публикуем ТОЛЬКО 1m
     */
    public void onKline(
            Long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volume,
            Instant closedAt
    ) {

        // =================================================
        // 🔒 ФИЛЬТР: ТОЛЬКО 1m
        // =================================================
        if (!"1m".equalsIgnoreCase(timeframe)) {
            return;
        }

        Instant time = closedAt != null ? closedAt : Instant.now();

        // =================================================
        // 1️⃣ ПИШЕМ В CANDLE PROVIDER
        // =================================================
        candleProvider.addCandle(
                chatId,
                symbol,
                "1m",
                time,
                open.doubleValue(),
                high.doubleValue(),
                low.doubleValue(),
                close.doubleValue(),
                volume.doubleValue()
        );

        // =================================================
        // 2️⃣ ПУБЛИКУЕМ В LIVE UI (ГРАФИК)
        // =================================================
        livePublisher.pushCandleOhlc(
                chatId,
                strategyType,
                symbol,
                "1m",
                open,
                high,
                low,
                close,
                volume,
                time
        );
    }

    /**
     * 💲 LIVE-тик цены
     * 🔥 КЛЮЧЕВО: тут же прокидываем цену в стратегию
     */
    public void onPriceTick(
            Long chatId,
            StrategyType strategyType,
            String symbol,
            BigDecimal price
    ) {
        if (price == null || price.signum() <= 0) {
            return;
        }

        Instant now = Instant.now();

        // =================================================
        // 1️⃣ UI / ГРАФИК
        // =================================================
        livePublisher.pushPriceTick(
                chatId,
                strategyType,
                symbol,
                price,
                now
        );

        // =================================================
        // 2️⃣ 🔥 STRATEGY (САМОЕ ВАЖНОЕ)
        // =================================================
        TradingStrategy strategy = strategyRegistry.get(strategyType);
        if (strategy != null) {
            strategy.onPriceUpdate(
                    chatId,
                    symbol, // ignored в ScalpingStrategyV4 — это ОК
                    price,
                    now
            );
        } else {
            log.warn("⚠ Strategy not found for type={}", strategyType);
        }
    }
}
