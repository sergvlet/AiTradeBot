
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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebChartFacadeImpl implements WebChartFacade {

    private static final int MIN_LIMIT = 10;
    private static final int MAX_LIMIT = 1500;
    private static final int MAX_TRADE_MARKERS = 300;
    private static final int EXCHANGE_PRELOAD_MAX_LIMIT = 1000;
    private static final long PRELOAD_COOLDOWN_MS = 15_000L;

    private final MarketDataStreamService streamService;
    private final ExchangeClientFactory exchangeClientFactory;
    private final StrategySettingsService settingsService;
    private final EmaCrossoverStrategySettingsService emaSettingsService;
    private final UiStrategyLayerService uiLayers;
    private final ChartTradeHistoryLoader tradeHistoryLoader;

    private final ConcurrentMap<ChartContextKey, Long> lastPreloadAtMs = new ConcurrentHashMap<>();

    @Override
    public StrategyChartDto buildChart(long chatId,
                                       StrategyType strategyType,
                                       String symbol,
                                       String timeframe,
                                       int limit) {

        if (chatId <= 0) {
            throw new IllegalArgumentException("chatId must be positive");
        }
        if (strategyType == null) {
            throw new IllegalArgumentException("strategyType must be provided");
        }
        if (symbol == null || symbol.isBlank()) {
            return empty();
        }

        final String sym = normalizeSymbol(symbol);
        if (sym == null) {
            return empty();
        }

        StrategySettings settings = loadSettings(chatId, strategyType);

        final String tf = resolveTimeframe(timeframe, settings);
        final int finalLimit = resolveLimit(limit, settings);
        final String exchange = normalizeExchange(settings != null ? settings.getExchangeName() : null);
        final NetworkType network = settings != null ? settings.getNetworkType() : null;

        StrategyChartDto.Layers snapshotLayers = uiLayers.buildLatestLayersForSnapshot(chatId, strategyType, sym);
        if (snapshotLayers == null) {
            snapshotLayers = StrategyChartDto.Layers.empty();
        }

        Map<String, Object> info = buildInfo(chatId, strategyType, settings);

        if (tf == null) {
            log.warn("⚠️ Chart: timeframe пустой (chatId={}, type={}, symbol={})", chatId, strategyType, sym);
            return StrategyChartDto.builder()
                    .candles(List.of())
                    .layers(snapshotLayers)
                    .info(info)
                    .build();
        }

        if (exchange == null || network == null) {
            log.warn("⚠️ Chart: пропуск кэша/прелоада — нет exchange/network (chatId={}, type={}, symbol={}, tf={}, ex={}, net={})",
                    chatId, strategyType, sym, tf, exchange, network);

            long[] fallbackRange = fallbackRange(tf, finalLimit);
            StrategyChartDto.Layers layersWithoutContext = mergeWithTradeHistory(
                    snapshotLayers,
                    List.of(),
                    fallbackRange[0],
                    fallbackRange[1],
                    MAX_TRADE_MARKERS
            );

            return StrategyChartDto.builder()
                    .candles(List.of())
                    .layers(layersWithoutContext)
                    .info(info)
                    .build();
        }

        List<Candle> candles = loadCandlesForChart(chatId, strategyType, exchange, network, sym, tf, finalLimit);
        candles = safeCandles(candles);

        long fromMs;
        long toMs;

        List<StrategyChartDto.CandleDto> candleDtos;
        Double lastPrice = null;

        if (!candles.isEmpty()) {
            int size = candles.size();
            int from = Math.max(0, size - finalLimit);
            List<Candle> slice = candles.subList(from, size);

            candleDtos = slice.stream()
                    .map(c -> StrategyChartDto.CandleDto.builder()
                            .time(c.getTime() / 1000L)
                            .open(c.getOpen())
                            .high(c.getHigh())
                            .low(c.getLow())
                            .close(c.getClose())
                            .build())
                    .toList();

            Candle last = slice.get(slice.size() - 1);
            lastPrice = last != null ? last.getClose() : null;

            fromMs = slice.get(0).getTime();
            toMs = last.getTime() + timeframeMs(tf);
        } else {
            candleDtos = List.of();
            long[] fallbackRange = fallbackRange(tf, finalLimit);
            fromMs = fallbackRange[0];
            toMs = fallbackRange[1];
        }

        List<StrategyChartDto.TradeMarker> tradeHistory = tradeHistoryLoader.loadTradeMarkers(
                chatId,
                strategyType,
                exchange,
                network,
                sym,
                tf,
                fromMs,
                toMs,
                MAX_TRADE_MARKERS
        );

        StrategyChartDto.Layers layers = mergeWithTradeHistory(snapshotLayers, tradeHistory, fromMs, toMs, MAX_TRADE_MARKERS);

        if (lastPrice == null) {
            lastPrice = inferLastPrice(layers);
        }

        return StrategyChartDto.builder()
                .candles(candleDtos)
                .lastPrice(lastPrice)
                .layers(layers)
                .info(info)
                .build();
    }

    private StrategySettings loadSettings(long chatId, StrategyType strategyType) {
        StrategySettings settings = null;
        try {
            settings = settingsService.getSettings(chatId, strategyType);
        } catch (Exception ignored) {
        }

        if (settings == null) {
            try {
                settings = settingsService.getOrCreate(chatId, strategyType);
            } catch (Exception ignored) {
            }
        }

        return settings;
    }

    private List<Candle> loadCandlesForChart(long chatId,
                                             StrategyType strategyType,
                                             String exchange,
                                             NetworkType network,
                                             String symbol,
                                             String timeframe,
                                             int limit) {

        List<Candle> exact = trimTail(
                safeCandles(streamService.getCandles(chatId, strategyType, exchange, network, symbol, timeframe)),
                limit
        );

        List<Candle> best = exact;

        List<Candle> fallbackBySymbol = trimTail(
                safeCandles(streamService.getCachedCandles(chatId, strategyType, symbol, timeframe, limit)),
                limit
        );

        if (fallbackBySymbol.size() > best.size()) {
            best = fallbackBySymbol;
        }

        boolean exactCacheMissing = exact.isEmpty();
        if (exactCacheMissing && shouldAttemptPreload(chatId, strategyType, exchange, network, symbol, timeframe)) {
            List<Candle> preloaded = tryPreloadFromExchange(chatId, strategyType, exchange, network, symbol, timeframe, limit);
            if (preloaded.size() > best.size()) {
                best = trimTail(preloaded, limit);
            }
        }

        if (best.isEmpty()) {
            List<Candle> direct = loadCandlesDirectFromExchange(exchange, network, symbol, timeframe, limit);
            if (!direct.isEmpty()) {
                try {
                    streamService.putCandles(chatId, strategyType, exchange, network, symbol, timeframe, direct);
                } catch (Exception e) {
                    log.debug("⚠️ Chart direct putCandles failed chatId={} type={} ex={} net={} {} {} : {}",
                            chatId, strategyType, exchange, network, symbol, timeframe, e.toString());
                }
                best = trimTail(direct, limit);
            }
        }

        return best;
    }

    private List<Candle> tryPreloadFromExchange(long chatId,
                                                StrategyType type,
                                                String exchange,
                                                NetworkType network,
                                                String symbol,
                                                String timeframe,
                                                int limit) {

        ChartContextKey key = new ChartContextKey(chatId, type, exchange, network, symbol, timeframe);
        lastPreloadAtMs.put(key, System.currentTimeMillis());

        List<Candle> preload = loadCandlesDirectFromExchange(exchange, network, symbol, timeframe, limit);
        if (preload.isEmpty()) {
            return List.of();
        }

        try {
            streamService.putCandles(chatId, type, exchange, network, symbol, timeframe, preload);
            log.info("📥 Chart preloaded: {} candles (chatId={}, type={}, ex={}, net={}, {} {}, limit={})",
                    preload.size(), chatId, type, exchange, network, symbol, timeframe, limit);
        } catch (Exception e) {
            log.error("❌ Chart preload failed (chatId={}, type={}, ex={}, net={}, {} {})",
                    chatId, type, exchange, network, symbol, timeframe, e);
        }

        return preload;
    }

    private List<Candle> loadCandlesDirectFromExchange(String exchange,
                                                       NetworkType network,
                                                       String symbol,
                                                       String timeframe,
                                                       int limit) {
        int requestLimit = Math.max(MIN_LIMIT, Math.min(limit, EXCHANGE_PRELOAD_MAX_LIMIT));

        ExchangeClient client;
        try {
            client = exchangeClientFactory.get(exchange, network);
        } catch (Exception e) {
            client = null;
        }

        if (client == null) {
            log.warn("⚠️ Chart preload пропущен: нет exchange client (ex={}, net={}, symbol={}, tf={})",
                    exchange, network, symbol, timeframe);
            return List.of();
        }

        try {
            List<ExchangeClient.Kline> klines = client.getKlines(symbol, timeframe, requestLimit);
            if (klines == null || klines.isEmpty()) {
                return List.of();
            }

            return klines.stream()
                    .filter(k -> k != null)
                    .sorted(Comparator.comparingLong(ExchangeClient.Kline::openTime))
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
        } catch (Exception e) {
            log.error("❌ Chart direct klines failed (ex={}, net={}, {} {})", exchange, network, symbol, timeframe, e);
            return List.of();
        }
    }

    private boolean shouldAttemptPreload(long chatId,
                                         StrategyType strategyType,
                                         String exchange,
                                         NetworkType network,
                                         String symbol,
                                         String timeframe) {
        ChartContextKey key = new ChartContextKey(chatId, strategyType, exchange, network, symbol, timeframe);
        long nowMs = System.currentTimeMillis();
        Long last = lastPreloadAtMs.get(key);
        if (last != null && nowMs - last < PRELOAD_COOLDOWN_MS) {
            return false;
        }
        return true;
    }

    private StrategyChartDto.Layers mergeWithTradeHistory(StrategyChartDto.Layers snapshot,
                                                          List<StrategyChartDto.TradeMarker> historyTrades,
                                                          long fromMs,
                                                          long toMs,
                                                          int maxTrades) {
        StrategyChartDto.Layers base = snapshot != null ? snapshot : StrategyChartDto.Layers.empty();
        List<StrategyChartDto.TradeMarker> merged = new ArrayList<>();

        if (historyTrades != null) {
            merged.addAll(historyTrades);
        }
        if (base.getTrades() != null) {
            merged.addAll(base.getTrades());
        }

        merged = merged.stream()
                .filter(t -> t != null && t.getTime() != null && t.getTime() >= fromMs && t.getTime() <= toMs)
                .sorted(Comparator.comparingLong(StrategyChartDto.TradeMarker::getTime))
                .toList();

        LinkedHashMap<String, StrategyChartDto.TradeMarker> uniq = new LinkedHashMap<>();
        for (StrategyChartDto.TradeMarker t : merged) {
            String key = String.valueOf(t.getSide()) + "|" + t.getTime() + "|" + t.getPrice() + "|" + t.getQty();
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

    private static Double inferLastPrice(StrategyChartDto.Layers layers) {
        if (layers == null) {
            return null;
        }

        if (layers.getTrades() != null && !layers.getTrades().isEmpty()) {
            StrategyChartDto.TradeMarker lastTrade = layers.getTrades().get(layers.getTrades().size() - 1);
            if (lastTrade != null && lastTrade.getPrice() != null) {
                return lastTrade.getPrice();
            }
        }

        if (layers.getPriceLines() != null && !layers.getPriceLines().isEmpty()) {
            Object lastLine = layers.getPriceLines().get(layers.getPriceLines().size() - 1);
            try {
                Object price = lastLine.getClass().getMethod("getPrice").invoke(lastLine);
                if (price instanceof Number n) {
                    double v = n.doubleValue();
                    if (Double.isFinite(v)) {
                        return v;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private static String resolveTimeframe(String timeframe, StrategySettings settings) {
        String tf = normalizeTimeframeOrNull(timeframe);
        if (tf != null) {
            return tf;
        }

        if (settings != null && settings.getTimeframe() != null && !settings.getTimeframe().isBlank()) {
            return normalizeTimeframeOrNull(settings.getTimeframe());
        }
        return null;
    }

    private static int resolveLimit(int limit, StrategySettings settings) {
        int resolved = limit;

        if (resolved < MIN_LIMIT || resolved > MAX_LIMIT) {
            Integer fromSettings = settings != null ? settings.getCachedCandlesLimit() : null;
            if (fromSettings != null) {
                resolved = fromSettings;
            }
        }

        if (resolved < MIN_LIMIT) {
            resolved = MIN_LIMIT;
        }
        if (resolved > MAX_LIMIT) {
            resolved = MAX_LIMIT;
        }

        return resolved;
    }

    private static long[] fallbackRange(String timeframe, int limit) {
        long now = System.currentTimeMillis();
        long span = Math.max(1L, limit) * Math.max(1L, timeframeMs(timeframe));
        return new long[]{Math.max(0L, now - span), now};
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

    private static String normalizeTimeframeOrNull(String timeframe) {
        if (timeframe == null) return null;
        String s = timeframe.trim().toLowerCase(Locale.ROOT);
        return s.isBlank() ? null : s;
    }

    private static List<Candle> safeCandles(List<Candle> list) {
        return list == null ? List.of() : list;
    }

    private static List<Candle> trimTail(List<Candle> list, int limit) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        int lim = Math.max(1, limit);
        if (list.size() <= lim) {
            return list;
        }
        return new ArrayList<>(list.subList(list.size() - lim, list.size()));
    }

    private static StrategyChartDto empty() {
        return StrategyChartDto.builder()
                .candles(List.of())
                .layers(StrategyChartDto.Layers.empty())
                .info(Map.of())
                .build();
    }

    private record ChartContextKey(long chatId,
                                   StrategyType strategyType,
                                   String exchange,
                                   NetworkType network,
                                   String symbol,
                                   String timeframe) {
        private ChartContextKey {
            exchange = exchange != null ? exchange.toUpperCase(Locale.ROOT) : null;
            symbol = symbol != null ? symbol.toUpperCase(Locale.ROOT) : null;
            timeframe = timeframe != null ? timeframe.toLowerCase(Locale.ROOT) : null;
        }
    }
}
