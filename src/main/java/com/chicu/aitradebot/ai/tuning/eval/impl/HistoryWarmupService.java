package com.chicu.aitradebot.ai.tuning.eval.impl;

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
     * Прогрев истории: грузим REST klines и кладём в MarketStreamManager как "закрытые" свечи.
     */
    public int warmup(long chatId,
                      StrategyType type,
                      String symbol,
                      String timeframe,
                      long startMs,
                      long endMs,
                      int limit) {

        String s = symbol.toUpperCase(Locale.ROOT);
        String tf = timeframe.toLowerCase(Locale.ROOT);

        StrategyEnvResolver.Env env = envResolver.resolve(chatId, type);
        ExchangeClient client = exchangeClientFactory.get(env.exchangeName(), env.networkType());

        try {
            List<ExchangeClient.Kline> klines = client.getKlines(s, tf, startMs, endMs, limit);
            if (klines == null || klines.isEmpty()) return 0;

            // кладём в кеш (закрытые свечи)
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
                    s, tf, klines.size(), env.exchangeName(), env.networkType());

            return klines.size();

        } catch (Exception e) {
            log.warn("Warmup failed: {} {} (exchange={} network={}): {}",
                    s, tf, env.exchangeName(), env.networkType(), e.getMessage());
            return 0;
        }
    }
}
