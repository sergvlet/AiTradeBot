package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.market.MarketStreamManager;
import com.chicu.aitradebot.market.model.Candle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryWarmupService {

    private final ExchangeClientFactory exchangeClientFactory;
    private final MarketStreamManager streamManager;
    private final StrategyEnvResolver envResolver;

    /**
     * ✅ BACKWARD COMPAT:
     * Старый метод — оставляем, но он НЕ гарантирует MAINNET/TESTNET,
     * потому что берёт окружение из envResolver.resolve(chatId, type).
     */
    public int warmup(long chatId,
                      StrategyType type,
                      String symbol,
                      String timeframe,
                      long startMs,
                      long endMs,
                      int limit) {

        return warmup(chatId, type, null, null, symbol, timeframe, startMs, endMs, limit);
    }

    /**
     * ✅ FIXED:
     * Прогрев истории с явным указанием exchange/network.
     * Если exchange/network не заданы — используем envResolver.resolve(chatId, type).
     */
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

        ExchangeClient client = exchangeClientFactory.get(exUsed, netUsed);

        try {
            List<ExchangeClient.Kline> klines = client.getKlines(s, tf, startMs, endMs, limit);
            if (klines == null || klines.isEmpty()) {
                log.info("🔥 Warmup empty: {} {} exchange={} network={} (0 candles)", s, tf, exUsed, netUsed);
                return 0;
            }

            for (ExchangeClient.Kline k : klines) {
                Candle candle = new Candle(
                        k.openTime(),
                        k.open(),
                        k.high(),
                        k.low(),
                        k.close(),
                        k.volume(),
                        true
                );
                streamManager.addCandle(s, tf, candle);
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
}
