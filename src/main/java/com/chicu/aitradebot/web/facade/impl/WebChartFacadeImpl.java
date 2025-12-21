package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.market.MarketService;
import com.chicu.aitradebot.market.stream.MarketDataStreamService;
import com.chicu.aitradebot.web.dto.StrategyChartDto;
import com.chicu.aitradebot.web.facade.WebChartFacade;
import com.chicu.aitradebot.web.ui.UiStrategyLayerService;
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
    private final MarketDataStreamService marketDataStreamService;
    private final UiStrategyLayerService uiLayerService;

    @Override
    public StrategyChartDto buildChart(
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            int limit
    ) {

        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Symbol must be provided for chart");
        }

        String finalSymbol = symbol.toUpperCase();

        log.info(
                "📊 BuildChart chatId={} strategy={} symbol={} tf={} limit={}",
                chatId, strategyType, finalSymbol, timeframe, limit
        );

        // =====================================================
        // 🔴 LIVE подписка (WS)
        // =====================================================
        try {
            marketDataStreamService.subscribeCandles(
                    chatId,
                    strategyType,
                    finalSymbol,
                    timeframe
            );
        } catch (Exception e) {
            log.warn("⚠ Live subscribe failed: {}", e.getMessage());
        }

        // =====================================================
        // 📜 История свечей
        // =====================================================
        List<ExchangeClient.Kline> klines = marketService.loadKlines(
                chatId,
                finalSymbol,
                timeframe,
                limit
        );

        List<StrategyChartDto.CandleDto> candles = klines.stream()
                .map(k -> StrategyChartDto.CandleDto.builder()
                        .time(k.openTime() / 1000)
                        .open(k.open())
                        .high(k.high())
                        .low(k.low())
                        .close(k.close())
                        .build()
                )
                .toList();

        Double lastPrice = candles.isEmpty()
                ? null
                : candles.get(candles.size() - 1).getClose();

        // =====================================================
        // 🧠 UI LAYERS (SNAPSHOT, БЕЗ БД)
        // =====================================================

        List<Double> levels =
                uiLayerService.loadLatestLevels(
                        chatId,
                        strategyType,
                        finalSymbol
                );

        StrategyChartDto.Zone zone = null;
        Map<String, Object> zoneMap =
                uiLayerService.loadLatestZone(
                        chatId,
                        strategyType,
                        finalSymbol
                );

        if (zoneMap != null
            && zoneMap.get("top") instanceof Number top
            && zoneMap.get("bottom") instanceof Number bottom) {

            zone = StrategyChartDto.Zone.builder()
                    .top(top.doubleValue())
                    .bottom(bottom.doubleValue())
                    .color((String) zoneMap.get("color"))
                    .build();
        }

        log.info(
                "🧠 UI snapshot loaded: levels={} zone={}",
                levels.size(),
                zone != null
        );

        // =====================================================
        // 📦 RESULT
        // =====================================================
        return StrategyChartDto.builder()
                .candles(candles)
                .lastPrice(lastPrice)
                .layers(
                        StrategyChartDto.Layers.builder()
                                .levels(levels)
                                .zone(zone)
                                .build()
                )
                .build();
    }
}
