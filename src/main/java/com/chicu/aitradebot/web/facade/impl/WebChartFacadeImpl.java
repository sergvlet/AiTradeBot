package com.chicu.aitradebot.web.facade.impl;

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

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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

        final String sym = symbol.trim().toUpperCase(Locale.ROOT);

        // 2) Берём актуальные StrategySettings (baseline)
        StrategySettings s = null;
        try {
            Optional<StrategySettings> opt = settingsService.findLatest(chatId, strategyType, null, null);
            s = opt.orElse(null);
        } catch (Exception e) {
            log.warn("⚠️ Chart: cannot read StrategySettings (chatId={}, type={})", chatId, strategyType, e);
        }

        // 3) tf и limit: приоритет параметров запроса, иначе из StrategySettings
        final String tf = resolveTimeframe(timeframe, s);
        final int finalLimit = resolveLimit(limit, s);

        if (tf == null || tf.isBlank()) {
            log.warn("⚠️ Chart: timeframe is empty (chatId={}, type={}, symbol={})", chatId, strategyType, sym);
            return empty();
        }

        // 4) Сначала пробуем кэш
        List<Candle> cached = safeCandles(streamService.getCandles(chatId, strategyType, sym, tf));

        // 5) Если кэша не хватает — preload из биржи (по exchange+network из StrategySettings)
        if (cached.size() < finalLimit) {
            tryPreloadFromExchange(chatId, strategyType, sym, tf, finalLimit, s);
        }

        // 6) Собираем результат из кэша (после preload)
        List<Candle> all = safeCandles(streamService.getCandles(chatId, strategyType, sym, tf));
        if (all.isEmpty()) return empty();

        // последние N свечей
        int size = all.size();
        int from = Math.max(0, size - finalLimit);
        List<Candle> slice = all.subList(from, size);

        List<StrategyChartDto.CandleDto> candleDtos = slice.stream()
                .map(c -> StrategyChartDto.CandleDto.builder()
                        .time(c.getTime() / 1000L) // контракт: seconds
                        .open(c.getOpen())
                        .high(c.getHigh())
                        .low(c.getLow())
                        .close(c.getClose())
                        .build()
                )
                .toList();

        double lastClose = slice.get(slice.size() - 1).getClose();

        // ВАЖНО: фасад графика не рисует “специфичные слои” (windowZone и т.п.)
        return StrategyChartDto.builder()
                .candles(candleDtos)
                .lastPrice(lastClose)
                .layers(StrategyChartDto.Layers.empty())
                .build();
    }

    private void tryPreloadFromExchange(
            long chatId,
            StrategyType type,
            String symbol,
            String timeframe,
            int limit,
            StrategySettings s
    ) {
        ExchangeClient client = resolveClientForChart(s);

        if (client == null) {
            log.warn("⚠️ Chart preload skipped: no exchange client (chatId={}, type={}, symbol={}, tf={})",
                    chatId, type, symbol, timeframe);
            return;
        }

        try {
            List<ExchangeClient.Kline> klines = client.getKlines(symbol, timeframe, limit);

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
                streamService.putCandles(chatId, type, symbol, timeframe, preload);
                log.info("📥 Chart preloaded: {} candles (chatId={}, type={}, {} {}, limit={})",
                        preload.size(), chatId, type, symbol, timeframe, limit);
            }
        } catch (Exception e) {
            log.error("❌ Chart preload failed (chatId={}, type={}, {} {})", chatId, type, symbol, timeframe, e);
        }
    }

    /**
     * ВАЖНО: для истории свечей НЕ должен требоваться “включённый exchange settings у пользователя”.
     * Поэтому берём клиента по exchange+network из StrategySettings.
     *
     * Чтобы не привязываться к точной сигнатуре ExchangeClientFactory#get(...),
     * аккуратно резолвим через reflection (поддерживает разные overload'ы).
     */
    private ExchangeClient resolveClientForChart(StrategySettings s) {
        if (s == null) return null;

        Object exchange = s.getExchangeName();
        Object network = s.getNetworkType();

        if (exchange == null) return null;
        if (exchange instanceof String exStr && exStr.isBlank()) return null;
        if (network == null) return null;

        try {
            for (Method m : exchangeClientFactory.getClass().getMethods()) {
                if (!m.getName().equals("get")) continue;
                if (m.getParameterCount() != 2) continue;
                if (!ExchangeClient.class.isAssignableFrom(m.getReturnType())) continue;

                Class<?> exParam = m.getParameterTypes()[0];
                Class<?> netParam = m.getParameterTypes()[1];

                Object exArg = adaptExchangeArg(exchange, exParam);
                Object netArg = adaptNetworkArg(network, netParam);

                if (exArg == null || netArg == null) continue;

                Object res = m.invoke(exchangeClientFactory, exArg, netArg);
                if (res instanceof ExchangeClient ec) return ec;
            }

            // fallback (на всякий): если есть только getByChat, но он может требовать exchange settings
            // return exchangeClientFactory.getByChat(s.getChatId());

            return null;
        } catch (Exception e) {
            log.warn("⚠️ Cannot resolve exchange client for chart: exchange={} network={}", exchange, network, e);
            return null;
        }
    }

    private Object adaptExchangeArg(Object exchangeValue, Class<?> targetType) {
        if (targetType.isInstance(exchangeValue)) return exchangeValue;

        // если targetType=String, а exchangeValue=Enum → берём name()
        if (targetType == String.class && exchangeValue instanceof Enum<?> en) {
            return en.name();
        }

        // если targetType=Enum, а exchangeValue=String → Enum.valueOf(...)
        if (targetType.isEnum() && exchangeValue instanceof String s) {
            String name = s.trim().toUpperCase(Locale.ROOT);
            if (name.isBlank()) return null;
            @SuppressWarnings({"rawtypes", "unchecked"})
            Class<? extends Enum> enumType = (Class<? extends Enum>) targetType;
            try {
                return Enum.valueOf(enumType, name);
            } catch (Exception ignored) {
                return null;
            }
        }

        // если targetType=Enum, а exchangeValue=Enum другого типа → пробуем по имени
        if (targetType.isEnum() && exchangeValue instanceof Enum<?> en) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Class<? extends Enum> enumType = (Class<? extends Enum>) targetType;
            try {
                return Enum.valueOf(enumType, en.name());
            } catch (Exception ignored) {
                return null;
            }
        }

        return null;
    }

    private Object adaptNetworkArg(Object networkValue, Class<?> targetType) {
        if (targetType.isInstance(networkValue)) return networkValue;

        // если targetType=String, а networkValue=Enum → name()
        if (targetType == String.class && networkValue instanceof Enum<?> en) {
            return en.name();
        }

        // если targetType=Enum, а networkValue=Enum другого типа → по имени
        if (targetType.isEnum() && networkValue instanceof Enum<?> en) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Class<? extends Enum> enumType = (Class<? extends Enum>) targetType;
            try {
                return Enum.valueOf(enumType, en.name());
            } catch (Exception ignored) {
                return null;
            }
        }

        return null;
    }

    private String resolveTimeframe(String timeframe, StrategySettings s) {
        String tf = (timeframe == null) ? null : timeframe.trim().toLowerCase(Locale.ROOT);
        if (tf != null && !tf.isBlank()) return tf;

        if (s != null && s.getTimeframe() != null && !s.getTimeframe().isBlank()) {
            return s.getTimeframe().trim().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private int resolveLimit(int limit, StrategySettings s) {
        int resolved = limit;

        // если лимит не задан/кривой — берём из StrategySettings
        if (resolved < MIN_LIMIT || resolved > MAX_LIMIT) {
            if (s != null && s.getCachedCandlesLimit() != null) {
                resolved = s.getCachedCandlesLimit();
            }
        }

        // финальная защита диапазона (контракт UI/графика)
        if (resolved < MIN_LIMIT) resolved = MIN_LIMIT;
        if (resolved > MAX_LIMIT) resolved = MAX_LIMIT;

        return resolved;
    }

    private List<Candle> safeCandles(List<Candle> list) {
        return list == null ? List.of() : list;
    }

    private StrategyChartDto empty() {
        return StrategyChartDto.builder()
                .candles(List.of())
                .layers(StrategyChartDto.Layers.empty())
                .build();
    }
}
