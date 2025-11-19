package com.chicu.aitradebot.strategy.smartfusion.components;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import com.chicu.aitradebot.market.ws.RealtimeStreamService;
import com.chicu.aitradebot.market.ws.CandleWebSocketHandler;
import com.chicu.aitradebot.market.ws.TradeFeedListener;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettings;
import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartFusionCandleService implements TradeFeedListener, CandleProvider {

    private final ExchangeClientFactory clientFactory;
    private final ExchangeSettingsService exchangeSettingsService;
    private final SmartFusionStrategySettingsService settingsService;
    private final CandleWebSocketHandler ws;
    private final RealtimeStreamService realtime;

    private final Map<String, List<CandleProvider.Candle>> historyCache = new ConcurrentHashMap<>();

    private static class LiveCandle {
        long bucket;
        double open, high, low, close;
        double volume;
    }

    private final Map<String, Map<String, LiveCandle>> live = new ConcurrentHashMap<>();

    private static final List<String> LIVE_TF = List.of("1s", "1m", "5m", "15m", "1h");

    private static final long LIVE_PUSH_INTERVAL_MS = 300;
    private final Map<String, Long> lastPush = new ConcurrentHashMap<>();

    // ============================================================
    // REALTIME: входящий трейд
    // ============================================================
    @Override
    public void onTrade(String symbol, BigDecimal price, long ts) {
        if (symbol == null || price == null) return;
        onTradeTick(symbol.toUpperCase(Locale.ROOT), ts, price.doubleValue());
    }

    // ============================================================
    // Обработка realtime-тиков
    // ============================================================
    public void onTradeTick(String symbol, long ts, double price) {

        for (String tf : LIVE_TF) {

            long sec = tfToSeconds(tf);
            if (sec <= 0) continue;

            long bucket = (ts / (sec * 1000)) * (sec * 1000);

            Map<String, LiveCandle> byTf =
                    live.computeIfAbsent(symbol, k -> new ConcurrentHashMap<>());

            LiveCandle lc = byTf.get(tf);

            // ---------------------------
            // 1️⃣ Новая свеча
            // ---------------------------
            if (lc == null || lc.bucket != bucket) {

                if (lc != null) {
                    CandleProvider.Candle closed = new CandleProvider.Candle(
                            lc.bucket,
                            lc.open, lc.high, lc.low, lc.close,
                            lc.volume
                    );

                    realtime.sendCandle(symbol, tf, closed);
                    ws.broadcastTick(symbol, tf, closed);
                }

                // создаём новую свечу
                lc = new LiveCandle();
                lc.bucket = bucket;
                lc.open = price;
                lc.high = price;
                lc.low = price;
                lc.close = price;
                lc.volume = 0;

                byTf.put(tf, lc);
                continue;
            }

            // ---------------------------
            // 2️⃣ Обновление текущей свечи
            // ---------------------------
            lc.close = price;
            if (price > lc.high) lc.high = price;
            if (price < lc.low)  lc.low = price;


            // ---------------------------
            // 3️⃣ Пушим только раз в 300 ms
            // ---------------------------
            long now = System.currentTimeMillis();
            long last = lastPush.getOrDefault(tf, 0L);
            if (now - last < LIVE_PUSH_INTERVAL_MS) continue;

            lastPush.put(tf, now);

            CandleProvider.Candle liveC = new CandleProvider.Candle(
                    bucket,
                    lc.open, lc.high, lc.low, lc.close,
                    lc.volume
            );

            realtime.sendCandle(symbol, tf, liveC);
            ws.broadcastTick(symbol, tf, liveC);
        }
    }

    private long tfToSeconds(String tf) {
        tf = tf.toLowerCase(Locale.ROOT);
        char unit = tf.charAt(tf.length() - 1);
        long n = Long.parseLong(tf.substring(0, tf.length() - 1));

        return switch (unit) {
            case 's' -> n;
            case 'm' -> n * 60;
            case 'h' -> n * 3600;
            case 'd' -> n * 86400;
            default -> 0;
        };
    }

    // ============================================================
    // Candles for FullChartApiController
    // ============================================================
    @Override
    public List<CandleProvider.Candle> getRecentCandles(long chatId, String symbol,
                                                        String timeframe, int limit) {

        SmartFusionStrategySettings s =
                (SmartFusionStrategySettings) settingsService.findByChatId(chatId)
                        .orElseThrow(() -> new IllegalStateException("Нет настроек SmartFusion"));

        s.setSymbol(symbol);
        s.setTimeframe(timeframe);
        s.setCandleLimit(limit);

        return getCandles(s);
    }

    public List<CandleProvider.Candle> getCandles(SmartFusionStrategySettings cfg) {

        String key = cfg.getExchange() + "|" + cfg.getNetworkType() + "|" +
                     cfg.getSymbol() + "|" + cfg.getTimeframe();

        int limit = Math.max(cfg.getCandleLimit(), 50);

        if (historyCache.containsKey(key) && historyCache.get(key).size() >= limit)
            return historyCache.get(key);

        try {
            var settings = exchangeSettingsService
                    .findByChatIdAndExchangeAndNetwork(
                            cfg.getChatId(),
                            cfg.getExchange(),
                            cfg.getNetworkType()
                    )
                    .orElseThrow();

            ExchangeClient client = clientFactory.getClient(settings);

            List<ExchangeClient.Kline> klines =
                    client.getKlines(cfg.getSymbol(), cfg.getTimeframe(), limit);

            List<CandleProvider.Candle> out = new ArrayList<>();

            for (ExchangeClient.Kline k : klines) {
                out.add(new CandleProvider.Candle(
                        k.openTime(),
                        k.open(), k.high(), k.low(), k.close(),
                        k.volume()
                ));
            }

            historyCache.put(key, out);
            return out;

        } catch (Exception e) {
            log.error("Ошибка загрузки свечей {} {}: {}", cfg.getSymbol(), cfg.getTimeframe(), e.getMessage());
            return List.of();
        }
    }

    public SmartFusionStrategySettings buildSettings(
            Long chatId, String symbol, String timeframe, Integer limit) {

        SmartFusionStrategySettings s = new SmartFusionStrategySettings();
        s.setChatId(chatId);
        s.setExchange("BINANCE");
        s.setSymbol(symbol);
        s.setTimeframe(timeframe);
        s.setNetworkType(NetworkType.MAINNET);
        s.setCandleLimit(limit != null ? limit : 300);
        return s;
    }

    public List<Map<String,Object>> calculateEma(List<CandleProvider.Candle> candles,
                                                 int period) {
        List<Map<String,Object>> out = new ArrayList<>();
        if (candles.isEmpty()) return out;

        double k = 2.0 / (period + 1);
        double prev = candles.get(0).close();

        for (CandleProvider.Candle c : candles) {
            prev = c.close() * k + prev * (1 - k);
            out.add(Map.of(
                    "time", c.getTime(),
                    "value", prev
            ));
        }

        return out;
    }

    // ============================================================
    // Last price
    // ============================================================
    public double getLastPrice(String symbol) {
        symbol = symbol.toUpperCase(Locale.ROOT);

        Map<String, LiveCandle> byTf = live.get(symbol);
        if (byTf == null) return 0;

        LiveCandle lc = byTf.get("1s");
        if (lc == null) return 0;

        return lc.close;
    }
}
