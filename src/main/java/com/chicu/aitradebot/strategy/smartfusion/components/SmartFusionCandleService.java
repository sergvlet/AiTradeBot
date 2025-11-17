package com.chicu.aitradebot.strategy.smartfusion.components;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import com.chicu.aitradebot.market.ws.CandleWebSocketHandler;
import com.chicu.aitradebot.market.ws.TradeFeedListener;
import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettings;
import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сервис свечей для SmartFusion + дашборда.
 * Поддерживает:
 *  - загрузку истории с биржи (getCandles / getRecentCandles)
 *  - live-свечи по разным таймфреймам (1s, 1m, 5m, 15m, 1h, ...)
 *  - отправку последней свечи в WebSocket /ws/candles для нужного таймфрейма
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmartFusionCandleService implements TradeFeedListener {

    /**
     * DTO свечи для SmartFusion + графика.
     * time = millis → совпадает с тем, что ждет фронт.
     */
    public record Candle(Instant ts, double open, double high, double low, double close) {
        public long getTime() {
            return ts.toEpochMilli();
        }
    }

    private final ExchangeClientFactory clientFactory;
    private final ExchangeSettingsService exchangeSettingsService;
    private final SmartFusionStrategySettingsService settingsService;
    private final CandleWebSocketHandler candleWebSocketHandler;

    /** Кэш исторических свечей: key = exchange|network|symbol|timeframe */
    private final Map<String, List<Candle>> cache = new ConcurrentHashMap<>();

    /**
     * Live-свеча в памяти.
     * Используется для всех таймфреймов, не только 1s.
     */
    private static class LiveCandle {
        long bucketStartSec;  // начало интервала (в секундах от эпохи)
        double open;
        double high;
        double low;
        double close;
    }

    /**
     * Live-кэш по таймфреймам:
     *  key: SYMBOL (верхний регистр)
     *  value: Map<timeframe, LiveCandle>
     *
     * Пример ключей timeframe: "1s", "1m", "5m", "15m", "1h"
     */
    private final Map<String, Map<String, LiveCandle>> liveByTf = new ConcurrentHashMap<>();

    // Набор таймфреймов, которые мы хотим поддерживать live (можно расширить)
    private static final List<String> LIVE_TIMEFRAMES = List.of(
            "1s", "1m", "5m", "15m", "1h"
    );

    // ======================================================================
    //  TradeFeedListener (из BinancePublicTradeStreamService)
    // ======================================================================

    @Override
    public void onTrade(String symbol, BigDecimal price, long ts) {
        if (symbol == null || price == null) return;
        onTradeTick(symbol, ts, price.doubleValue());
    }

    // ======================================================================
    //  PUBLIC: live тик → обновление свечей по всем таймфреймам + WS
    // ======================================================================

    /**
     * Собирает live-свечи по всем поддерживаемым таймфреймам
     * (1s, 1m, 5m, 15m, 1h, ...) и пушит в WebSocket /ws/candles.
     *
     * @param symbol   "BTCUSDT"
     * @param tsMillis timestamp трейда в миллисекундах
     * @param price    цена сделки
     */
    public void onTradeTick(String symbol, long tsMillis, double price) {
        if (symbol == null || symbol.isBlank()) return;

        String sym = symbol.toUpperCase(Locale.ROOT);

        // Для каждого поддерживаемого таймфрейма считаем свой "бакет"
        for (String tf : LIVE_TIMEFRAMES) {
            long tfSec = timeframeToSeconds(tf);
            if (tfSec <= 0) {
                continue;
            }

            long sec = tsMillis / 1000L;
            long bucketStartSec = (sec / tfSec) * tfSec;

            Map<String, LiveCandle> byTf = liveByTf.computeIfAbsent(sym, k -> new ConcurrentHashMap<>());

            LiveCandle lc = byTf.compute(tf, (key, old) -> {
                if (old == null || old.bucketStartSec != bucketStartSec) {
                    LiveCandle nc = new LiveCandle();
                    nc.bucketStartSec = bucketStartSec;
                    nc.open = price;
                    nc.high = price;
                    nc.low = price;
                    nc.close = price;
                    return nc;
                } else {
                    old.close = price;
                    if (price > old.high) old.high = price;
                    if (price < old.low)   old.low = price;
                    return old;
                }
            });

            SmartFusionCandleService.Candle candle = new SmartFusionCandleService.Candle(
                    Instant.ofEpochSecond(lc.bucketStartSec),
                    lc.open,
                    lc.high,
                    lc.low,
                    lc.close
            );

            try {
                // NEW API ✔
                candleWebSocketHandler.broadcastTick(sym, tf, candle);
            } catch (Exception e) {
                log.error("❌ Ошибка отправки WS свечи {} {}: {}", sym, tf, e.getMessage());
            }
        }
    }

    /**
     * Перевод строки таймфрейма (1s, 1m, 5m, 1h, 1d, ...) в секунды.
     * Поддерживает формат: <число><s|m|h|d|w>.
     */
    private long timeframeToSeconds(String tf) {
        if (tf == null || tf.isBlank()) return 0L;

        tf = tf.trim().toLowerCase(Locale.ROOT);
        char unit = tf.charAt(tf.length() - 1);
        String numPart = tf.substring(0, tf.length() - 1);

        long amount;
        try {
            amount = Long.parseLong(numPart);
        } catch (NumberFormatException e) {
            log.warn("⚠️ Не удалось распарсить таймфрейм: {}", tf);
            return 0L;
        }

        return switch (unit) {
            case 's' -> amount;
            case 'm' -> amount * 60L;
            case 'h' -> amount * 3600L;
            case 'd' -> amount * 86400L;
            case 'w' -> amount * 7 * 86400L;
            default -> {
                log.warn("⚠️ Неизвестная единица таймфрейма: {} (tf={})", unit, tf);
                yield 0L;
            }
        };
    }

    // ======================================================================
    //  ИСТОРИЯ ДЛЯ ДАШБОРДА / СТРАТЕГИИ
    // ======================================================================

    public List<Candle> getRecentCandles(long chatId, int limit) {
        SmartFusionStrategySettings cfg = (SmartFusionStrategySettings) settingsService.findByChatId(chatId)
                .orElseThrow(() -> new IllegalStateException(
                        "SmartFusion настройки не найдены для chatId=" + chatId));

        cfg.setCandleLimit(limit);
        return getCandles(cfg);
    }

    public List<Candle> getCandles(SmartFusionStrategySettings cfg) {
        String exchange = Optional.ofNullable(cfg.getExchange()).orElse("BINANCE");
        NetworkType network = Optional.ofNullable(cfg.getNetworkType()).orElse(NetworkType.MAINNET);
        String symbol = Optional.ofNullable(cfg.getSymbol()).orElse("BTCUSDT");
        String timeframe = Optional.ofNullable(cfg.getTimeframe()).orElse("1h");
        int limit = Math.max(cfg.getCandleLimit(), 50);

        String key = String.join("|", exchange, network.name(), symbol, timeframe);
        List<Candle> cached = cache.get(key);
        if (cached != null && cached.size() >= limit) {
            log.debug("♻️ Используются кэшированные свечи [{} {}] {}", exchange, network, symbol);
            return cached;
        }

        try {
            ExchangeSettings settings = exchangeSettingsService
                    .findByChatIdAndExchangeAndNetwork(cfg.getChatId(), exchange, network)
                    .orElseThrow(() -> new IllegalStateException(
                            "Настройки не найдены: chatId=" + cfg.getChatId()
                            + ", exchange=" + exchange + ", network=" + network));

            ExchangeClient client = clientFactory.getClient(settings);

            List<ExchangeClient.Kline> klines = client.getKlines(symbol, timeframe, limit);

            List<Candle> candles = new ArrayList<>();
            for (ExchangeClient.Kline k : klines) {
                candles.add(new Candle(
                        Instant.ofEpochMilli(k.openTime()),
                        k.open(),
                        k.high(),
                        k.low(),
                        k.close()
                ));
            }

            cache.put(key, candles);
            log.info("📊 Загружено {} свечей для {} [{} / {}]", candles.size(), symbol, exchange, network);
            return candles;

        } catch (Exception e) {
            log.error("❌ Ошибка загрузки свечей {} {}: {}", exchange, symbol, e.getMessage(), e);
            return generateFallbackData(symbol, limit);
        }
    }

    /** Последняя цена символа (по live 1s, затем по кэшу). */
    public double getLastPrice(String symbol) {
        if (symbol == null) return 0.0;
        String sym = symbol.toUpperCase(Locale.ROOT);

        Map<String, LiveCandle> byTf = liveByTf.get(sym);
        if (byTf != null) {
            LiveCandle oneSec = byTf.get("1s");
            if (oneSec != null) {
                return oneSec.close;
            }
        }

        return cache.entrySet().stream()
                .filter(e -> e.getKey().contains("|" + sym + "|"))
                .map(Map.Entry::getValue)
                .flatMap(List::stream)
                .reduce((first, second) -> second)
                .map(Candle::close)
                .orElse(0.0);
    }

    /** Фоллбек-генератор свечей при недоступности API. */
    private List<Candle> generateFallbackData(String symbol, int limit) {
        List<Candle> candles = new ArrayList<>();
        Random rnd = new Random();
        double price = 100.0 + rnd.nextDouble() * 10.0;
        Instant now = Instant.now();

        for (int i = 0; i < limit; i++) {
            double change = rnd.nextGaussian() * 0.3;
            price = Math.max(1.0, price + change);
            candles.add(new Candle(
                    now.minusSeconds(60L * (limit - i)),
                    price * 0.999,
                    price * 1.002,
                    price * 0.998,
                    price
            ));
        }

        log.warn("⚠️ Используются сгенерированные свечи для {} (offline mode)", symbol);
        return candles;
    }

    /** 📈 Расчёт EMA для графика. */
    public List<Map<String, Object>> calculateEma(List<Candle> candles, int period) {
        if (candles == null || candles.isEmpty()) return Collections.emptyList();

        List<Map<String, Object>> ema = new ArrayList<>();
        double multiplier = 2.0 / (period + 1);
        double prevEma = candles.get(0).close();

        for (Candle c : candles) {
            double current = (c.close() - prevEma) * multiplier + prevEma;
            prevEma = current;
            ema.add(Map.of(
                    "time", c.ts().toEpochMilli(),
                    "value", current
            ));
        }

        return ema;
    }

    /** Построение временных настроек для графика (используется ChartApiController). */
    public SmartFusionStrategySettings buildSettings(Long chatId, String symbol, String timeframe, int limit) {
        SmartFusionStrategySettings s = new SmartFusionStrategySettings();
        s.setChatId(chatId);
        s.setSymbol(symbol);
        s.setTimeframe(timeframe != null ? timeframe : "15m");
        s.setCandleLimit(Math.max(50, limit));
        // Биржа/сеть подтянутся из ExchangeSettingsService при getCandles(...)
        s.setExchange("BINANCE");
        s.setNetworkType(NetworkType.TESTNET);
        return s;
    }
}
