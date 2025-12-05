package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.market.MarketService;
import com.chicu.aitradebot.web.facade.WebMarketFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebMarketFacadeImpl implements WebMarketFacade {

    private final MarketService marketService;

    // -------------------------------
    // 1) Первоначальная загрузка свечей
    // -------------------------------
    @Override
    public List<CandlePoint> loadInitialCandles(
            Long chatId,
            String symbol,
            String timeframe,
            int limit
    ) {
        List<ExchangeClient.Kline> klines =
                marketService.loadKlines(chatId, symbol, timeframe, limit);

        return klines.stream()
                .map(k -> new CandlePoint(
                        k.openTime(),
                        BigDecimal.valueOf(k.open()),
                        BigDecimal.valueOf(k.high()),
                        BigDecimal.valueOf(k.low()),
                        BigDecimal.valueOf(k.close()),
                        BigDecimal.valueOf(k.volume())
                ))
                .toList();
    }

    // -------------------------------
    // 2) Догрузка свечей назад
    // -------------------------------
    @Override
    public List<CandlePoint> loadMoreCandles(
            Long chatId,
            String symbol,
            String timeframe,
            Instant to,
            int limit
    ) {
        // загружаем больше свечей (например limit * 2)
        List<ExchangeClient.Kline> klines =
                marketService.loadKlines(chatId, symbol, timeframe, limit * 2);

        long toMs = to.toEpochMilli();

        // фильтруем по openTime <= to
        List<ExchangeClient.Kline> filtered = klines.stream()
                .filter(k -> k.openTime() <= toMs)
                .sorted((a, b) -> Long.compare(a.openTime(), b.openTime()))
                .toList();

        // берём только последние limit
        if (filtered.size() > limit) {
            filtered = filtered.subList(filtered.size() - limit, filtered.size());
        }

        return filtered.stream()
                .map(k -> new CandlePoint(
                        k.openTime(),
                        BigDecimal.valueOf(k.open()),
                        BigDecimal.valueOf(k.high()),
                        BigDecimal.valueOf(k.low()),
                        BigDecimal.valueOf(k.close()),
                        BigDecimal.valueOf(k.volume())
                ))
                .toList();
    }

    // -------------------------------
    // 3) Последняя цена
    // -------------------------------
    @Override
    public PricePoint getLastPrice(Long chatId, String symbol) {
        BigDecimal last = marketService.getCurrentPrice(chatId, symbol);
        return new PricePoint(System.currentTimeMillis(), last);
    }

    // -------------------------------
    // 4) Тренд (рост/падение)
    // -------------------------------
    @Override
    public TrendInfo getTrendInfo(Long chatId, String symbol, String timeframe) {

        List<ExchangeClient.Kline> klines =
                marketService.loadKlines(chatId, symbol, timeframe, 50);

        if (klines.size() < 2) {
            return new TrendInfo(false, BigDecimal.ZERO);
        }

        double first = klines.get(0).close();
        double last = klines.get(klines.size() - 1).close();

        if (first == 0) {
            return new TrendInfo(false, BigDecimal.ZERO);
        }

        BigDecimal changePct =
                BigDecimal.valueOf((last - first) / first * 100.0);

        boolean up = changePct.compareTo(BigDecimal.ZERO) >= 0;

        return new TrendInfo(up, changePct);
    }
}
