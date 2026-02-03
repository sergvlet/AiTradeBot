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
import java.util.Comparator;
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
        if (chatId == null || limit <= 0) return Collections.emptyList();

        String sym = normalizeSymbol(symbol);
        String tf  = normalizeTimeframe(timeframe);

        try {
            ExchangeClient client = exchangeClientFactory.getByChat(chatId);

            List<ExchangeClient.Kline> klines = client.getKlines(sym, tf, limit);

            // ✅ стабильный порядок времени ↑
            return klines.stream()
                    .map(MarketHistoryServiceImpl::toCandle)
                    .sorted(Comparator.comparingLong(CandleProvider.Candle::time))
                    .toList();

        } catch (Exception e) {
            logWarn("loadInitial", chatId, sym, tf, limit, null, e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<CandleProvider.Candle> loadMore(Long chatId,
                                                String symbol,
                                                String timeframe,
                                                Instant to,
                                                int limit) {
        if (chatId == null || limit <= 0 || to == null) return Collections.emptyList();

        String sym = normalizeSymbol(symbol);
        String tf  = normalizeTimeframe(timeframe);

        try {
            ExchangeClient client = exchangeClientFactory.getByChat(chatId);

            long endExclusive = to.toEpochMilli();
            // ✅ делаем endInclusive, чтобы точно было "до to"
            long endInclusive = Math.max(0L, endExclusive - 1);

            // startTimeMs=0 → "с начала времён", но клиент обязан уважать endTimeMs
            List<ExchangeClient.Kline> klines = client.getKlines(sym, tf, 0L, endInclusive, limit);

            return klines.stream()
                    .map(MarketHistoryServiceImpl::toCandle)
                    .sorted(Comparator.comparingLong(CandleProvider.Candle::time))
                    .toList();

        } catch (Exception e) {
            logWarn("loadMore", chatId, sym, tf, limit, to, e);
            return Collections.emptyList();
        }
    }

    private static CandleProvider.Candle toCandle(ExchangeClient.Kline k) {
        return new CandleProvider.Candle(
                k.openTime(),
                k.open(),
                k.high(),
                k.low(),
                k.close(),
                k.volume()
        );
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase();
    }

    private static String normalizeTimeframe(String timeframe) {
        return timeframe == null ? "" : timeframe.trim().toLowerCase();
    }

    private static void logWarn(String op,
                                Long chatId,
                                String symbol,
                                String timeframe,
                                int limit,
                                Instant to,
                                Exception e) {
        if (to != null) {
            log.warn("⚠️ MarketHistory {} failed: chatId={}, symbol={}, tf={}, limit={}, to={}, msg={}",
                    op, chatId, symbol, timeframe, limit, to, e.getMessage());
        } else {
            log.warn("⚠️ MarketHistory {} failed: chatId={}, symbol={}, tf={}, limit={}, msg={}",
                    op, chatId, symbol, timeframe, limit, e.getMessage());
        }

        if (log.isDebugEnabled()) {
            log.debug("Stacktrace ({})", op, e);
        }
    }
}
