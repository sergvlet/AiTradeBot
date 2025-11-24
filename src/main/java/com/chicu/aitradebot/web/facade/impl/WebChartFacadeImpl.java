package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.market.MarketService;
import com.chicu.aitradebot.web.dto.StrategyChartDto;
import com.chicu.aitradebot.web.facade.WebChartFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WebChartFacadeImpl implements WebChartFacade {

    private final MarketService marketService;

    @Override
    public StrategyChartDto buildChart(long chatId, String strategyType, int limit, String timeframe) {

        // 1. Загружаем универсальные свечи (CandleProvider.Candle: double-поля)
        var candles = marketService.loadCandles(chatId, "BTCUSDT", timeframe, limit);

        // 2. Конвертация в StrategyChartDto.CandleDto
        // ВАЖНО: не объявляем явный тип List<...>, чтобы не ловить List<Object>
        var candleDtos = candles.stream()
                .map(c -> StrategyChartDto.CandleDto.builder()
                        .time(c.time())          // long
                        .open(c.open())          // double
                        .high(c.high())          // double
                        .low(c.low())            // double
                        .close(c.close())        // double
                        .volume(c.volume())      // double
                        .build()
                )
                .toList();

        // 3. Собираем DTO графика (остальные поля пока пустые)
        return StrategyChartDto.builder()
                .symbol("BTCUSDT")
                .timeframe(timeframe)
                .candles(candleDtos)

                .equity(List.of())
                .kpis(Map.of())
                .monthlyPnl(Map.of())

                .emaFast(List.of())
                .emaSlow(List.of())
                .ema20(List.of())
                .ema50(List.of())
                .bollinger(Map.of())
                .atr(List.of())
                .supertrend(List.of())

                .trades(List.of())
                .tpLevels(List.of())
                .slLevels(List.of())
                .stats(Map.of())

                .lastPrice(
                        candleDtos.isEmpty()
                                ? 0.0
                                : candleDtos.get(candleDtos.size() - 1).getClose()
                )
                .serverTime(System.currentTimeMillis())
                .build();
    }
}
