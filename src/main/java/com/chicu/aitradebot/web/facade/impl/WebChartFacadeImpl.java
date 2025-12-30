package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.market.stream.MarketDataStreamService;
import com.chicu.aitradebot.web.dto.StrategyChartDto;
import com.chicu.aitradebot.web.facade.WebChartFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebChartFacadeImpl implements WebChartFacade {

    private static final int DEFAULT_LIMIT = 500;
    private static final String DEFAULT_TF = "1m";

    private final MarketDataStreamService streamService;
    private final ExchangeClientFactory exchangeClientFactory;

    @Override
    public StrategyChartDto buildChart(
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            int limit
    ) {

        // =====================================================
        // 1️⃣ NORMALIZE INPUT
        // =====================================================

        if (symbol == null || symbol.isBlank()) {
            return StrategyChartDto.builder()
                    .candles(List.of())
                    .build();
        }

        String finalSymbol = symbol.trim().toUpperCase(Locale.ROOT);

        String tf = (timeframe == null || timeframe.isBlank())
                ? DEFAULT_TF
                : timeframe.trim().toLowerCase(Locale.ROOT);

        int finalLimit = limit > 0 ? limit : DEFAULT_LIMIT;

        log.debug(
                "📊 Chart snapshot chatId={} type={} symbol={} tf={} limit={}",
                chatId, strategyType, finalSymbol, tf, finalLimit
        );

        // =====================================================
        // 2️⃣ 🔥 PRELOAD HISTORY IF CACHE EMPTY
        // =====================================================

        List<Candle> cachedCandles =
                streamService.getCandles(chatId, strategyType, finalSymbol, tf);

        if (cachedCandles.size() < finalLimit) {
            try {
                ExchangeClient client = exchangeClientFactory.getByChat(chatId);

                List<ExchangeClient.Kline> klines =
                        client.getKlines(finalSymbol, tf, finalLimit);

                List<Candle> preload = klines.stream()
                        .map(k -> new Candle(
                                k.openTime(), // ⏱ ms — OK, храним в cache в ms
                                k.open(),
                                k.high(),
                                k.low(),
                                k.close(),
                                k.volume(),
                                true
                        ))
                        .toList();

                streamService.putCandles(
                        chatId,
                        strategyType,
                        finalSymbol,
                        tf,
                        preload
                );

                log.info(
                        "📥 Chart preload {} candles for {} {} (chatId={}, strategy={})",
                        preload.size(), finalSymbol, tf, chatId, strategyType
                );

            } catch (Exception e) {
                log.error(
                        "❌ Chart preload failed {} {} chatId={}",
                        finalSymbol, tf, chatId, e
                );
            }
        }

        // =====================================================
        // 3️⃣ READ FROM CACHE (ЕДИНЫЙ ИСТОЧНИК)
        // =====================================================

        List<Candle> candles =
                streamService.getCandles(chatId, strategyType, finalSymbol, tf);

        // =====================================================
// 4️⃣ MAP → DTO (FIX TIME UNIT)
// =====================================================

        List<StrategyChartDto.CandleDto> candleDtos = candles.stream()
                .limit(finalLimit)
                .map(c -> StrategyChartDto.CandleDto.builder()
                        // ❗ БЫЛО: c.getTime()
                        // ✅ СТАЛО: seconds
                        .time(c.getTime() / 1000)
                        .open(c.getOpen())
                        .high(c.getHigh())
                        .low(c.getLow())
                        .close(c.getClose())
                        .build()
                )
                .toList();

        // =====================================================
        // 5️⃣ RESULT
        // =====================================================

        return StrategyChartDto.builder()
                .candles(candleDtos)
                .build();
    }
}
