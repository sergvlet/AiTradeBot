package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.market.MarketStreamManager;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.strategy.core.CandleProvider;
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

    private final CandleProvider candleProvider;
    private final MarketStreamManager streamManager;
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
            return StrategyChartDto.builder().candles(List.of()).build();
        }

        String finalSymbol = symbol.trim().toUpperCase(Locale.ROOT);

        String tf = (timeframe == null || timeframe.isBlank())
                ? DEFAULT_TF
                : timeframe.trim().toLowerCase(Locale.ROOT);

        if (tf.startsWith("kline_")) {
            tf = tf.substring(6);
        }

        int finalLimit = limit > 0 ? limit : DEFAULT_LIMIT;

        log.debug(
                "📊 Chart snapshot chatId={} type={} symbol={} tf={} limit={}",
                chatId, strategyType, finalSymbol, tf, finalLimit
        );

        // =====================================================
        // 2️⃣ 🔥 PRELOAD HISTORY IF CACHE EMPTY
        // =====================================================

        int cached = streamManager.getCandles(finalSymbol, tf, finalLimit).size();

        if (cached < finalLimit) {
            try {
                ExchangeClient client = exchangeClientFactory.getByChat(chatId);

                List<ExchangeClient.Kline> klines =
                        client.getKlines(finalSymbol, tf, finalLimit);

                for (ExchangeClient.Kline k : klines) {
                    streamManager.addCandle(
                            finalSymbol,
                            tf,
                            new Candle(
                                    k.openTime(),
                                    k.open(),
                                    k.high(),
                                    k.low(),
                                    k.close(),
                                    k.volume(),
                                    false
                            )
                    );
                }

                log.info("📥 Chart preload {} candles for {} {}", klines.size(), finalSymbol, tf);

            } catch (Exception e) {
                log.error("❌ Chart preload failed {} {}", finalSymbol, tf, e);
            }
        }

        // =====================================================
        // 3️⃣ READ FROM CACHE (ЕДИНЫЙ ИСТОЧНИК)
        // =====================================================

        List<CandleProvider.Candle> candles =
                candleProvider.getRecentCandles(
                        chatId,
                        finalSymbol,
                        tf,
                        finalLimit
                );

        // =====================================================
        // 4️⃣ MAP → DTO
        // =====================================================

        List<StrategyChartDto.CandleDto> candleDtos = candles.stream()
                .map(c -> StrategyChartDto.CandleDto.builder()
                        .time(c.time())
                        .open(c.open())
                        .high(c.high())
                        .low(c.low())
                        .close(c.close())
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
