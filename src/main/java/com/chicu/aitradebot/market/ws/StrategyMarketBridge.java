package com.chicu.aitradebot.market.ws;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyMarketBridge {

    private final StrategyLivePublisher livePublisher;

    /**
     * LIVE-свеча стратегии (ОБЯЗАТЕЛЬНО с StrategyType)
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

        log.debug("📡 LIVE CANDLE → chatId={}, type={}, {} {} O:{} C:{}",
                chatId, strategyType, symbol, timeframe, open, close);

        livePublisher.pushCandleOhlc(
                chatId,
                strategyType,
                symbol,
                timeframe,
                open, high, low, close,
                volume,
                closedAt
        );
    }

    /**
     * LIVE-тик цены (опционально)
     */
    public void onPriceTick(
            Long chatId,
            StrategyType strategyType,
            String symbol,
            BigDecimal price
    ) {

        livePublisher.pushPriceTick(
                chatId,
                strategyType,
                symbol,
                price,
                Instant.now()
        );
    }
}
