package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.market.MarketService;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.web.dto.StrategyChartDto;
import com.chicu.aitradebot.web.facade.WebChartFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebChartFacadeImpl implements WebChartFacade {

    private final MarketService marketService;
    private final StrategySettingsService settingsService;

    @Override
    public StrategyChartDto buildChart(long chatId,
                                       String strategyType,
                                       String symbol,
                                       String timeframe,
                                       int limit) {

        // 1️⃣ Загружаем настройки стратегии
        var typeEnum = Enum.valueOf(
                com.chicu.aitradebot.common.enums.StrategyType.class,
                strategyType
        );

        var settings = settingsService.getOrCreate(chatId, typeEnum);

        // приоритет: переданный symbol → symbol из стратегии
        String exSymbol = (symbol != null && !symbol.isBlank())
                ? symbol
                : settings.getSymbol();

        String exchange = settings.getExchangeName();

        log.info("📊 BuildChart: chatId={} strategy={} exchange={} symbol={} tf={} limit={}",
                chatId, strategyType, exchange, exSymbol, timeframe, limit);

        // 2️⃣ Загружаем свечи реальной биржи и нужного символа
        List<ExchangeClient.Kline> klines = marketService.loadKlines(
                chatId,
                exSymbol,
                timeframe,
                limit
        );

        // 3️⃣ Преобразуем к DTO
        var candleDtos = klines.stream()
                .map(k -> StrategyChartDto.CandleDto.builder()
                        .time(k.openTime())
                        .open(k.open())
                        .high(k.high())
                        .low(k.low())
                        .close(k.close())
                        .volume(k.volume())
                        .build()
                )
                .toList();

        double last = candleDtos.isEmpty()
                ? 0.0
                : candleDtos.get(candleDtos.size() - 1).getClose();

        // 4️⃣ Формируем полный объект графика
        return StrategyChartDto.builder()
                .symbol(exSymbol)
                .timeframe(timeframe)
                .candles(candleDtos)
                .lastPrice(last)
                .serverTime(System.currentTimeMillis())

                // Placeholder поля
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

                .build();
    }
}
