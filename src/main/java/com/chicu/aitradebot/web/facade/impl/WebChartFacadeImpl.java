package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.market.stream.MarketDataStreamService;
import com.chicu.aitradebot.strategy.scalping.ScalpingStrategySettings;
import com.chicu.aitradebot.strategy.scalping.ScalpingStrategySettingsService;
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
    private final ScalpingStrategySettingsService scalpingSettingsService;

    @Override
    public StrategyChartDto buildChart(
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            int limit
    ) {

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

        List<Candle> cachedCandles =
                streamService.getCandles(chatId, strategyType, finalSymbol, tf);

        if (cachedCandles.size() < finalLimit) {
            try {
                ExchangeClient client = exchangeClientFactory.getByChat(chatId);
                List<ExchangeClient.Kline> klines =
                        client.getKlines(finalSymbol, tf, finalLimit);

                List<Candle> preload = klines.stream()
                        .map(k -> new Candle(
                                k.openTime(),
                                k.open(),
                                k.high(),
                                k.low(),
                                k.close(),
                                k.volume(),
                                true
                        ))
                        .toList();

                streamService.putCandles(chatId, strategyType, finalSymbol, tf, preload);

            } catch (Exception e) {
                log.error("❌ Chart preload failed", e);
            }
        }

        List<Candle> candles =
                streamService.getCandles(chatId, strategyType, finalSymbol, tf);

        List<StrategyChartDto.CandleDto> candleDtos = candles.stream()
                .limit(finalLimit)
                .map(c -> StrategyChartDto.CandleDto.builder()
                        .time(c.getTime() / 1000)
                        .open(c.getOpen())
                        .high(c.getHigh())
                        .low(c.getLow())
                        .close(c.getClose())
                        .build()
                )
                .toList();

        StrategyChartDto.Layers layers = StrategyChartDto.Layers.empty();

        if (strategyType == StrategyType.SCALPING && !candles.isEmpty()) {
            ScalpingStrategySettings settings = scalpingSettingsService.getOrCreate(chatId);

            double thresholdPct = settings.getPriceChangeThreshold();
            double close = candles.get(candles.size() - 1).getClose();

            double high = close * (1 + thresholdPct / 100.0);
            double low  = close * (1 - thresholdPct / 100.0);

            StrategyChartDto.Zone zone = StrategyChartDto.Zone.builder()
                    .top(high)
                    .bottom(low)
                    .color("rgba(100,116,139,0.25)")
                    .build();

            layers = StrategyChartDto.Layers.builder()
                    .zone(zone)
                    .levels(List.of())
                    .build();
        }

        return StrategyChartDto.builder()
                .candles(candleDtos)
                .layers(layers)
                .build();
    }

}
