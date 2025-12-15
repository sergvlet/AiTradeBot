package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.market.MarketService;
import com.chicu.aitradebot.market.stream.MarketDataStreamService;
import com.chicu.aitradebot.service.StrategySettingsService;
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
    private final StrategySettingsService settingsService;
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

        var settings = settingsService.getOrCreate(chatId, strategyType);

        String finalSymbol = (symbol != null && !symbol.isBlank())
                ? symbol
                : settings.getSymbol();

        log.info(
                "📊 BuildChart chatId={} strategy={} symbol={} tf={} limit={}",
                chatId, strategyType, finalSymbol, timeframe, limit
        );

        // =====================================================
        // 🔴 LIVE подписка
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
        // 🧠 UI LAYERS — ЧЕРЕЗ SERVICE (БЕЗ JSON)
        // =====================================================

        // LEVELS
        List<Double> levels =
                uiLayerService.loadLatestLevels(
                        chatId,
                        strategyType,
                        finalSymbol
                );

        // ZONE
        StrategyChartDto.Zone zone = null;
        Map<String, Object> zoneMap =
                uiLayerService.loadLatestZone(
                        chatId,
                        strategyType,
                        finalSymbol
                );

        if (zoneMap != null) {
            zone = StrategyChartDto.Zone.builder()
                    .top(((Number) zoneMap.get("top")).doubleValue())
                    .bottom(((Number) zoneMap.get("bottom")).doubleValue())
                    .color((String) zoneMap.get("color"))
                    .build();
        }

        log.info(
                "🧠 UI layers loaded: levels={} zone={}",
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
