package com.chicu.aitradebot.strategy.smartfusion.components;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettings;
import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionCandleService;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartFusionCandleService {

    /** DTO свечи */
    public record Candle(Instant ts, double open, double high, double low, double close) {
        public Instant getTime() { return ts; } // 👈 используется в контроллере
    }

    private final ExchangeClientFactory clientFactory;
    private final ExchangeSettingsService exchangeSettingsService;
    private final SmartFusionStrategySettingsService settingsService;

    /** Кэш свечей: key = exchange|network|symbol|timeframe */
    private final Map<String, List<Candle>> cache = new ConcurrentHashMap<>();

    /**
     * ✅ Новый метод: позволяет контроллеру получить свечи по chatId.
     */
    public List<Candle> getRecentCandles(long chatId, int limit) {
        SmartFusionStrategySettings cfg = settingsService.findByChatId(chatId)
                .orElseThrow(() -> new IllegalStateException("SmartFusion настройки не найдены для chatId=" + chatId));

        // перезаписываем лимит свечей, если передан явно
        cfg.setCandleLimit(limit);
        return getCandles(cfg);
    }

    /**
     * Основная загрузка свечей для конкретной конфигурации стратегии.
     */
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
                            "Настройки не найдены: chatId=" + cfg.getChatId() +
                            ", exchange=" + exchange + ", network=" + network));

            ExchangeClient client = clientFactory.getClient(settings);
            List<ExchangeClient.Kline> klines = client.getKlines(symbol, timeframe, limit);

            List<Candle> candles = new ArrayList<>();
            for (ExchangeClient.Kline k : klines) {
                candles.add(new Candle(
                        Instant.ofEpochMilli(k.openTime()),
                        k.open(), k.high(), k.low(), k.close()
                ));
            }

            cache.put(key, candles);
            log.info("📊 Загружено {} свечей для {} [{} / {}]", candles.size(), symbol, exchange, network);
            return candles;

        } catch (Exception e) {
            log.error("❌ Ошибка загрузки свечей {} {}: {}", exchange, symbol, e.getMessage());
            return generateFallbackData(symbol, limit);
        }
    }

    /** Последняя цена символа */
    public double getLastPrice(String symbol) {
        return cache.values().stream()
                .flatMap(List::stream)
                .reduce((first, second) -> second)
                .map(Candle::close)
                .orElse(0.0);
    }

    /** Генератор фейковых свечей при недоступности API */
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
                    price * 0.999, price * 1.002, price * 0.998, price
            ));
        }

        log.warn("⚠️ Используются сгенерированные свечи для {} (offline mode)", symbol);
        return candles;
    }
    /**
     * 📈 Расчёт EMA (экспоненциальной скользящей средней)
     * для отображения на графике
     */
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
    public SmartFusionStrategySettings buildSettings(Long chatId, String symbol, String timeframe, int limit) {
        SmartFusionStrategySettings s = new SmartFusionStrategySettings();
        s.setChatId(chatId);
        s.setSymbol(symbol);
        s.setTimeframe(timeframe != null ? timeframe : "15m");
        s.setCandleLimit(Math.max(50, limit));
        // разумные дефолты (биржа/сеть подтянутся в getCandles(...) через ExchangeSettingsService)
        s.setExchange("BINANCE");
        s.setNetworkType(com.chicu.aitradebot.common.enums.NetworkType.TESTNET);
        return s;
    }


}
