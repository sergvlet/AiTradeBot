package com.chicu.aitradebot.market.impl;

import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.market.MarketHistoryService;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketHistoryServiceImpl implements MarketHistoryService {

    private final ExchangeClientFactory exchangeClientFactory;

    @Override
    public List<CandleProvider.Candle> loadInitial(Long chatId,
                                                   String symbol,
                                                   String timeframe,
                                                   int limit) {

        try {
            ExchangeClient client = exchangeClientFactory.getByChat(chatId);

            List<ExchangeClient.Kline> klines = client.getKlines(symbol, timeframe, limit);

            return klines.stream()
                    .map(k -> new CandleProvider.Candle(
                            k.openTime(),
                            k.open(),
                            k.high(),
                            k.low(),
                            k.close(),
                            k.volume()
                    ))
                    .toList();

        } catch (Exception e) {
            log.error("❌ Ошибка loadInitial(): {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<CandleProvider.Candle> loadMore(Long chatId,
                                                String symbol,
                                                String timeframe,
                                                Instant to,
                                                int limit) {

        try {
            ExchangeClient client = exchangeClientFactory.getByChat(chatId);

            List<ExchangeClient.Kline> klines =
                    client.getKlines(symbol, timeframe, limit + 50);

            return klines.stream()
                    .filter(k -> k.openTime() < to.toEpochMilli())
                    .sorted((a, b) -> Long.compare(b.openTime(), a.openTime()))
                    .limit(limit)
                    .map(k -> new CandleProvider.Candle(
                            k.openTime(),
                            k.open(),
                            k.high(),
                            k.low(),
                            k.close(),
                            k.volume()
                    ))
                    .toList();

        } catch (Exception e) {
            log.error("❌ Ошибка loadMore(): {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
