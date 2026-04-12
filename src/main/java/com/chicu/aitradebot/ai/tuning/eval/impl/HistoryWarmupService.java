package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.ai.tuning.eval.StrategyEnvResolver;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.market.MarketStreamManager;
import com.chicu.aitradebot.market.model.Candle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryWarmupService {

    private final ExchangeClientFactory exchangeClientFactory;
    private final MarketStreamManager streamManager;
    private final StrategyEnvResolver envResolver;

    public int warmup(long chatId,
                      StrategyType type,
                      String symbol,
                      String timeframe,
                      long startMs,
                      long endMs,
                      int limit) {

        return warmup(chatId, type, null, null, symbol, timeframe, startMs, endMs, limit);
    }

    public int warmup(long chatId,
                      StrategyType type,
                      String exchange,
                      NetworkType network,
                      String symbol,
                      String timeframe,
                      long startMs,
                      long endMs,
                      int limit) {

        if (symbol == null || symbol.isBlank() || timeframe == null || timeframe.isBlank()) {
            log.warn("🔥 Warmup skipped: blank symbol/timeframe (chatId={} type={} symbol='{}' tf='{}')",
                    chatId, type, symbol, timeframe);
            return 0;
        }

        String s = symbol.trim().toUpperCase(Locale.ROOT);
        String tf = timeframe.trim().toLowerCase(Locale.ROOT);

        final String exUsed;
        final NetworkType netUsed;

        if (exchange != null && !exchange.trim().isEmpty() && network != null) {
            exUsed = exchange.trim().toUpperCase(Locale.ROOT);
            netUsed = network;
        } else {
            StrategyEnvResolver.Env env = envResolver.resolve(chatId, type);
            exUsed = env.exchangeName();
            netUsed = env.networkType();
        }

        if (exUsed == null || exUsed.isBlank() || netUsed == null) {
            log.warn("🔥 Warmup skipped: env unresolved (chatId={} type={} ex={} net={})",
                    chatId, type, exUsed, netUsed);
            return 0;
        }

        ExchangeClient client = exchangeClientFactory.get(exUsed, netUsed);

        try {
            List<ExchangeClient.Kline> klines = client.getKlines(s, tf, startMs, endMs, limit);
            if (klines == null || klines.isEmpty()) {
                log.info("🔥 Warmup empty: {} {} exchange={} network={} (0 candles)", s, tf, exUsed, netUsed);
                return 0;
            }

            for (ExchangeClient.Kline k : klines) {
                if (k == null) continue;

                Candle candle = new Candle(
                        k.openTime(),
                        k.open(),
                        k.high(),
                        k.low(),
                        k.close(),
                        k.volume(),
                        true
                );

                if (!tryAddCandleToStream(exUsed, netUsed, s, tf, candle)) {
                    streamManager.addCandle(s, tf, candle);
                }
            }

            log.info("🔥 Warmup done: {} {} candles={} exchange={} network={}",
                    s, tf, klines.size(), exUsed, netUsed);

            return klines.size();

        } catch (Exception e) {
            log.warn("🔥 Warmup failed: {} {} (exchange={} network={}): {}",
                    s, tf, exUsed, netUsed, e.getMessage());
            return 0;
        }
    }

    private boolean tryAddCandleToStream(String exchange,
                                         NetworkType network,
                                         String symbol,
                                         String timeframe,
                                         Candle candle) {
        if (streamManager == null) return false;

        if (tryInvoke(streamManager,
                "addCandle",
                new Class<?>[]{String.class, NetworkType.class, String.class, String.class, Candle.class},
                new Object[]{exchange, network, symbol, timeframe, candle})) {
            return true;
        }

        if (tryInvoke(streamManager,
                "addCandle",
                new Class<?>[]{String.class, String.class, String.class, String.class, Candle.class},
                new Object[]{exchange, (network != null ? network.name() : null), symbol, timeframe, candle})) {
            return true;
        }

        if (tryInvoke(streamManager,
                "addCandle",
                new Class<?>[]{NetworkType.class, String.class, String.class, Candle.class},
                new Object[]{network, symbol, timeframe, candle})) {
            return true;
        }

        return false;
    }

    private boolean tryInvoke(Object target, String method, Class<?>[] sig, Object[] args) {
        try {
            Method m = target.getClass().getMethod(method, sig);
            m.invoke(target, args);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}