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
 *  - live 1s-свечи из BinancePublicTradeStreamService.onTrade(...)
 *  - отправку последней свечи в WebSocket /ws/candles
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmartFusionCandleService implements TradeFeedListener {

    /**
     * DTO свечи для SmartFusion + графика.
     * Важно: getTime() → Jackson/Map даёт поле "time", которое ожидает JS.
     */
    public record Candle(Instant ts, double open, double high, double low, double close) {
        /** time в миллисекундах для фронта. */
        public long getTime() {
            return ts.toEpochMilli();
        }
    }

    private final ExchangeClientFactory clientFactory;
    private final ExchangeSettingsService exchangeSettingsService;
    private final SmartFusionStrategySettingsService settingsService;
    private final CandleWebSocketHandler candleWebSocketHandler;

    /** Кэш свечей: key = exchange|network|symbol|timeframe */
    private final Map<String, List<Candle>> cache = new ConcurrentHashMap<>();

    /** Live 1-секундные свечи по символу (timeframe = 1s). */
    private static class LiveCandle {
        long openSec;
        double open;
        double high;
        double low;
        double close;
    }

    /** key = SYMBOL (верхний регистр), только для 1s. */
    private final Map<String, LiveCandle> live1s = new ConcurrentHashMap<>();

    // ======================================================================
    //  TradeFeedListener (из BinancePublicTradeStreamService)
    // ======================================================================

    @Override
    public void onTrade(String symbol, BigDecimal price, long ts) {
        if (symbol == null || price == null) return;
        onTradeTick(symbol, ts, price.doubleValue());
    }



    // ======================================================================
    //  PUBLIC: live тик с Binance → 1s свеча → WS
    // ======================================================================

    /**
     * Собирает 1-секундную свечу и пушит её в WebSocket /ws/candles.
     *
     * @param symbol  "BTCUSDT"
     * @param tsMillis timestamp трейда в миллисекундах
     * @param price   цена сделки
     */
    public void onTradeTick(String symbol, long tsMillis, double price) {
        if (symbol == null || symbol.isBlank()) return;

        String sym = symbol.toUpperCase(Locale.ROOT);
        long sec = tsMillis / 1000L; // бакет 1 секунда

        LiveCandle lc = live1s.compute(sym, (k, old) -> {
            if (old == null || old.openSec != sec) {
                LiveCandle nc = new LiveCandle();
                nc.openSec = sec;
                nc.open = price;
                nc.high = price;
                nc.low = price;
                nc.close = price;
                return nc;
            } else {
                old.close = price;
                if (price > old.high) old.high = price;
                if (price < old.low) old.low = price;
                return old;
            }
        });

        Candle candle = new Candle(
                Instant.ofEpochSecond(lc.openSec),
                lc.open,
                lc.high,
                lc.low,
                lc.close
        );

        // отправляем только для timeframe=1s (именно его выбирает фронт для live)
        try {
            candleWebSocketHandler.broadcastTick(sym, "1s", candle);
        } catch (Exception e) {
            log.error("❌ Ошибка отправки WS свечи {}: {}", sym, e.getMessage());
        }
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

            // Если биржа не поддерживает timeframe — client сам бросит ошибку
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

    /** Последняя цена символа (по кэшу + live 1s). */
    public double getLastPrice(String symbol) {
        if (symbol == null) return 0.0;
        String sym = symbol.toUpperCase(Locale.ROOT);

        LiveCandle lc = live1s.get(sym);
        if (lc != null) return lc.close;

        return cache.entrySet().stream()
                .filter(e -> e.getKey().contains("|" + sym + "|"))
                .map(Map.Entry::getValue)
                .flatMap(List::stream)
                .reduce((first, second) -> second)
                .map(Candle::close)
                .orElse(0.0);
    }

    /** Генератор фейковых свечей при недоступности API. */
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
