package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.market.stream.MarketDataStreamService;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.ema.EmaCrossoverStrategySettings;
import com.chicu.aitradebot.strategy.ema.EmaCrossoverStrategySettingsService;
import com.chicu.aitradebot.web.dto.StrategyChartDto;
import com.chicu.aitradebot.web.facade.WebChartFacade;
import com.chicu.aitradebot.web.ui.UiStrategyLayerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebChartFacadeImpl implements WebChartFacade {

    private static final int MIN_LIMIT = 10;
    private static final int MAX_LIMIT = 1500;
    private static final int MAX_TRADE_MARKERS = 300;

    private final MarketDataStreamService streamService;
    private final ExchangeClientFactory exchangeClientFactory;
    private final StrategySettingsService settingsService;
    private final EmaCrossoverStrategySettingsService emaSettingsService;
    private final UiStrategyLayerService uiLayers;
    private final ChartTradeHistoryLoader tradeHistoryLoader;

    @Override
    public StrategyChartDto buildChart(
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            int limit
    ) {
        if (chatId <= 0) throw new IllegalArgumentException("chatId must be positive");
        if (strategyType == null) throw new IllegalArgumentException("strategyType must be provided");
        if (symbol == null || symbol.isBlank()) return empty();

        final String sym = normalizeSymbol(symbol);
        if (sym == null) return empty();

        StrategySettings s = null;
        try {
            s = settingsService.getSettings(chatId, strategyType);
        } catch (Exception ignored) { }
        if (s == null) {
            try {
                s = settingsService.getOrCreate(chatId, strategyType);
            } catch (Exception ignored) { }
        }

        final String tf = resolveTimeframe(timeframe, s);
        final int finalLimit = resolveLimit(limit, s);

        if (tf == null) {
            log.warn("⚠️ Chart: timeframe пустой (chatId={}, type={}, symbol={})", chatId, strategyType, sym);
            return empty();
        }

        final String exchange = normalizeExchange(s != null ? s.getExchangeName() : null);
        final NetworkType network = (s != null ? s.getNetworkType() : null);

        if (exchange == null || network == null) {
            log.warn("⚠️ Chart: пропуск кэша/прелоада — нет exchange/network (chatId={}, type={}, symbol={}, tf={}, ex={}, net={})",
                    chatId, strategyType, sym, tf, exchange, network);
            return empty();
        }

        List<Candle> cached = safeCandles(streamService.getCandles(chatId, strategyType, exchange, network, sym, tf));
        if (cached.size() < finalLimit) {
            tryPreloadFromExchange(chatId, strategyType, exchange, network, sym, tf, finalLimit);
        }

        List<Candle> all = safeCandles(streamService.getCandles(chatId, strategyType, exchange, network, sym, tf));
        if (all.isEmpty()) return empty();

        int size = all.size();
        int from = Math.max(0, size - finalLimit);
        List<Candle> slice = all.subList(from, size);

        List<StrategyChartDto.CandleDto> candleDtos = slice.stream()
                .map(c -> StrategyChartDto.CandleDto.builder()
                        .time(c.getTime() / 1000L)
                        .open(c.getOpen())
                        .high(c.getHigh())
                        .low(c.getLow())
                        .close(c.getClose())
                        .build())
                .toList();

        double lastClose = slice.get(slice.size() - 1).getClose();
        long fromMs = slice.get(0).getTime();
        long toMs = slice.get(slice.size() - 1).getTime() + timeframeMs(tf);

        StrategyChartDto.Layers layers = uiLayers.buildLatestLayersForSnapshot(chatId, strategyType, sym);
        layers = mergeWithTradeHistory(layers,
                tradeHistoryLoader.loadTradeMarkers(chatId, strategyType, exchange, network, sym, tf, fromMs, toMs, MAX_TRADE_MARKERS),
                fromMs,
                toMs,
                MAX_TRADE_MARKERS);

        return StrategyChartDto.builder()
                .candles(candleDtos)
                .lastPrice(lastClose)
                .layers(layers)
                .info(buildInfo(chatId, strategyType, s))
                .build();
    }

    private StrategyChartDto.Layers mergeWithTradeHistory(StrategyChartDto.Layers snapshot,
                                                          List<StrategyChartDto.TradeMarker> historyTrades,
                                                          long fromMs,
                                                          long toMs,
                                                          int maxTrades) {
        StrategyChartDto.Layers base = snapshot != null ? snapshot : StrategyChartDto.Layers.empty();
        List<StrategyChartDto.TradeMarker> merged = new ArrayList<>();
        if (historyTrades != null) merged.addAll(historyTrades);
        if (base.getTrades() != null) merged.addAll(base.getTrades());

        merged = merged.stream()
                .filter(t -> t != null && t.getTime() != null && t.getTime() >= fromMs && t.getTime() <= toMs)
                .sorted(Comparator.comparingLong(StrategyChartDto.TradeMarker::getTime))
                .toList();

        LinkedHashMap<String, StrategyChartDto.TradeMarker> uniq = new LinkedHashMap<>();
        for (StrategyChartDto.TradeMarker t : merged) {
            String key = String.valueOf(t.getSide()) + "|" + t.getTime() + "|" + t.getPrice();
            uniq.put(key, t);
        }
        merged = new ArrayList<>(uniq.values());

        if (merged.size() > maxTrades) {
            merged = new ArrayList<>(merged.subList(merged.size() - maxTrades, merged.size()));
        }

        return StrategyChartDto.Layers.builder()
                .levels(base.getLevels() != null ? base.getLevels() : List.of())
                .zone(base.getZone())
                .tpSl(base.getTpSl())
                .windowZone(base.getWindowZone())
                .priceLines(base.getPriceLines() != null ? base.getPriceLines() : List.of())
                .trades(merged)
                .build();
    }

    private Map<String, Object> buildInfo(long chatId, StrategyType strategyType, StrategySettings settings) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (settings != null) {
            out.put("timeframe", settings.getTimeframe());
            out.put("cachedCandlesLimit", settings.getCachedCandlesLimit());
            out.put("exchange", settings.getExchangeName());
            out.put("network", settings.getNetworkType() != null ? settings.getNetworkType().name() : null);
            out.put("symbol", settings.getSymbol());
        }

        if (strategyType == StrategyType.EMA_CROSSOVER) {
            try {
                EmaCrossoverStrategySettings ema = emaSettingsService.getOrCreate(chatId);
                if (ema != null) {
                    out.put("emaFast", ema.getEmaFast());
                    out.put("emaSlow", ema.getEmaSlow());
                    out.put("confirmBars", ema.getConfirmBars());
                    out.put("maxSpreadPct", ema.getMaxSpreadPct());
                    out.put("takeProfitPct", ema.getTakeProfitPct());
                    out.put("stopLossPct", ema.getStopLossPct());
                }
            } catch (Exception e) {
                log.debug("⚠️ Chart info EMA load failed chatId={} err={}", chatId, e.toString());
            }
        }

        return out;
    }

    private void tryPreloadFromExchange(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network,
            String symbol,
            String timeframe,
            int limit
    ) {
        ExchangeClient client;
        try {
            client = exchangeClientFactory.get(exchange, network);
        } catch (Exception e) {
            client = null;
        }

        if (client == null) {
            log.warn("⚠️ Chart preload пропущен: нет exchange client (chatId={}, type={}, ex={}, net={}, symbol={}, tf={})",
                    chatId, type, exchange, network, symbol, timeframe);
            return;
        }

        try {
            List<ExchangeClient.Kline> klines = client.getKlines(symbol, timeframe, limit);
            if (klines == null || klines.isEmpty()) return;

            klines = klines.stream()
                    .filter(k -> k != null)
                    .sorted(Comparator.comparingLong(ExchangeClient.Kline::openTime))
                    .toList();

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

            if (!preload.isEmpty()) {
                streamService.putCandles(chatId, type, exchange, network, symbol, timeframe, preload);
                log.info("📥 Chart preloaded: {} candles (chatId={}, type={}, ex={}, net={}, {} {}, limit={})",
                        preload.size(), chatId, type, exchange, network, symbol, timeframe, limit);
            }
        } catch (Exception e) {
            log.error("❌ Chart preload failed (chatId={}, type={}, ex={}, net={}, {} {})",
                    chatId, type, exchange, network, symbol, timeframe, e);
        }
    }

    private static String resolveTimeframe(String timeframe, StrategySettings s) {
        String tf = (timeframe == null) ? null : timeframe.trim().toLowerCase(Locale.ROOT);
        if (tf != null && !tf.isBlank()) return tf;

        if (s != null && s.getTimeframe() != null && !s.getTimeframe().isBlank()) {
            String x = s.getTimeframe().trim().toLowerCase(Locale.ROOT);
            return x.isBlank() ? null : x;
        }
        return null;
    }

    private static int resolveLimit(int limit, StrategySettings s) {
        int resolved = limit;

        if (resolved < MIN_LIMIT || resolved > MAX_LIMIT) {
            Integer fromSettings = (s != null ? s.getCachedCandlesLimit() : null);
            if (fromSettings != null) resolved = fromSettings;
        }

        if (resolved < MIN_LIMIT) resolved = MIN_LIMIT;
        if (resolved > MAX_LIMIT) resolved = MAX_LIMIT;

        return resolved;
    }

    private static long timeframeMs(String tf) {
        if (tf == null || tf.isBlank()) return 60_000L;
        String s = tf.trim().toLowerCase(Locale.ROOT);
        if (s.length() < 2) return 60_000L;
        char unit = s.charAt(s.length() - 1);
        long value;
        try {
            value = Long.parseLong(s.substring(0, s.length() - 1));
        } catch (Exception e) {
            return 60_000L;
        }
        return switch (unit) {
            case 's' -> value * 1_000L;
            case 'm' -> value * 60_000L;
            case 'h' -> value * 3_600_000L;
            case 'd' -> value * 86_400_000L;
            case 'w' -> value * 604_800_000L;
            default -> 60_000L;
        };
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT).replace("/", "");
        return s.isBlank() ? null : s;
    }

    private static String normalizeExchange(String exchange) {
        if (exchange == null) return null;
        String s = exchange.trim().toUpperCase(Locale.ROOT);
        return s.isBlank() ? null : s;
    }

    private static List<Candle> safeCandles(List<Candle> list) {
        return list == null ? List.of() : list;
    }

    private static StrategyChartDto empty() {
        return StrategyChartDto.builder()
                .candles(List.of())
                .layers(StrategyChartDto.Layers.empty())
                .info(Map.of())
                .build();
    }
}
