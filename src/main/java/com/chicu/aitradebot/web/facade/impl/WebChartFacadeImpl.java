// src/main/java/com/chicu/aitradebot/web/facade/impl/WebChartFacadeImpl.java
package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.market.stream.MarketDataStreamService;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.web.dto.StrategyChartDto;
import com.chicu.aitradebot.web.facade.WebChartFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebChartFacadeImpl implements WebChartFacade {

    private static final int MIN_LIMIT = 10;
    private static final int MAX_LIMIT = 1500;

    private final MarketDataStreamService streamService;
    private final ExchangeClientFactory exchangeClientFactory;
    private final StrategySettingsService settingsService;

    @Override
    public StrategyChartDto buildChart(
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            int limit
    ) {
        // 1) Базовая валидация
        if (chatId <= 0) throw new IllegalArgumentException("chatId must be positive");
        if (strategyType == null) throw new IllegalArgumentException("strategyType must be provided");
        if (symbol == null || symbol.isBlank()) return empty();

        final String sym = normalizeSymbol(symbol);
        if (sym == null) return empty();

        // 2) Берём StrategySettings именно этого типа (без findAllByChatId + сортировок)
        StrategySettings s = null;
        try {
            s = settingsService.getSettings(chatId, strategyType);
        } catch (Exception ignored) { }
        if (s == null) {
            try {
                s = settingsService.getOrCreate(chatId, strategyType);
            } catch (Exception ignored) { }
        }

        // 3) tf и limit: приоритет параметров запроса, иначе из StrategySettings
        final String tf = resolveTimeframe(timeframe, s);
        final int finalLimit = resolveLimit(limit, s);

        if (tf == null) {
            log.warn("⚠️ Chart: timeframe пустой (chatId={}, type={}, symbol={})", chatId, strategyType, sym);
            return empty();
        }

        // 4) Контекст (exchange/network) обязателен для кэша/прелоада
        final String exchange = normalizeExchange(s != null ? s.getExchangeName() : null);
        final NetworkType network = (s != null ? s.getNetworkType() : null);

        if (exchange == null || network == null) {
            log.warn("⚠️ Chart: пропуск кэша/прелоада — нет exchange/network (chatId={}, type={}, symbol={}, tf={}, ex={}, net={})",
                    chatId, strategyType, sym, tf, exchange, network);
            return empty();
        }

        // 5) Сначала пробуем кэш (СТРОГО по ex+net)
        List<Candle> cached = safeCandles(streamService.getCandles(chatId, strategyType, exchange, network, sym, tf));

        // 6) Если кэша не хватает — preload из биржи
        if (cached.size() < finalLimit) {
            tryPreloadFromExchange(chatId, strategyType, exchange, network, sym, tf, finalLimit);
        }

        // 7) Берём результат из кэша (после preload)
        List<Candle> all = safeCandles(streamService.getCandles(chatId, strategyType, exchange, network, sym, tf));
        if (all.isEmpty()) return empty();

        int size = all.size();
        int from = Math.max(0, size - finalLimit);
        List<Candle> slice = all.subList(from, size);

        List<StrategyChartDto.CandleDto> candleDtos = slice.stream()
                .map(c -> StrategyChartDto.CandleDto.builder()
                        .time(c.getTime() / 1000L) // seconds
                        .open(c.getOpen())
                        .high(c.getHigh())
                        .low(c.getLow())
                        .close(c.getClose())
                        .build()
                )
                .toList();

        double lastClose = slice.get(slice.size() - 1).getClose();

        return StrategyChartDto.builder()
                .candles(candleDtos)
                .lastPrice(lastClose)
                .layers(StrategyChartDto.Layers.empty()) // слои отдаём через replay (пункт 2/3)
                .build();
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

            // на всякий случай — сортируем по времени
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

        // 0 или мусор — берём из settings
        if (resolved < MIN_LIMIT || resolved > MAX_LIMIT) {
            Integer fromSettings = (s != null ? s.getCachedCandlesLimit() : null);
            if (fromSettings != null) resolved = fromSettings;
        }

        if (resolved < MIN_LIMIT) resolved = MIN_LIMIT;
        if (resolved > MAX_LIMIT) resolved = MAX_LIMIT;

        return resolved;
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
                .build();
    }
}