package com.chicu.aitradebot.market.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.market.MarketService;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * MarketServiceImpl
 *
 * Реальная реализация на базе ExchangeClientFactory.
 * Сейчас берём данные с BINANCE MAINNET (публичные эндпоинты).
 *
 * chatId пока не используем для выбора биржи/сети — этого достаточно
 * для веб-дашборда и живого графика.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketServiceImpl implements MarketService {

    private final ExchangeClientFactory exchangeClientFactory;

    /**
     * Берём клиент Binance MAINNET для рыночных данных.
     * (Публичные /api/v3/klines и /ticker/price не требуют ключей.)
     */
    private ExchangeClient client() {
        return exchangeClientFactory.getClient("BINANCE", NetworkType.MAINNET);
    }

    // -----------------------------------------------------
    // 1) Текущая цена
    // -----------------------------------------------------
    @Override
    public BigDecimal getCurrentPrice(Long chatId, String symbol) {
        try {
            double price = client().getPrice(symbol);
            return BigDecimal.valueOf(price);
        } catch (Exception e) {
            log.error("MarketService.getCurrentPrice({}, {}) error", chatId, symbol, e);
            return BigDecimal.ZERO;
        }
    }

    // -----------------------------------------------------
    // 2) Основная загрузка свечей
    // -----------------------------------------------------
    @Override
    public List<CandleProvider.Candle> loadCandles(
            Long chatId,
            String symbol,
            String timeframe,
            int limit
    ) {
        try {
            log.debug("MarketService.loadCandles({}, {}, {}, {})",
                    chatId, symbol, timeframe, limit);

            // Забираем klines с биржи
            List<ExchangeClient.Kline> klines =
                    client().getKlines(symbol, timeframe, limit);

            List<CandleProvider.Candle> result = new ArrayList<>(klines.size());

            for (ExchangeClient.Kline k : klines) {
                result.add(new CandleProvider.Candle(
                        k.openTime(),   // openTime в миллисекундах
                        k.open(),
                        k.high(),
                        k.low(),
                        k.close(),
                        k.volume()
                ));
            }

            // На всякий случай сортируем по времени
            result.sort(Comparator.comparingLong(CandleProvider.Candle::time));

            return result;

        } catch (Exception e) {
            log.error("MarketService.loadCandles({}, {}, {}, {}) error",
                    chatId, symbol, timeframe, limit, e);
            return List.of();
        }
    }

    // -----------------------------------------------------
    // 3) Догрузка назад
    // -----------------------------------------------------
    @Override
    public List<CandleProvider.Candle> loadMoreCandles(
            Long chatId,
            String symbol,
            String timeframe,
            Instant to,
            int limit
    ) {
        try {
            log.debug("MarketService.loadMoreCandles({}, {}, {}, {}, {})",
                    chatId, symbol, timeframe, to, limit);

            // Забираем чуть больше, чем нужно, и фильтруем по to
            int fetchLimit = Math.max(limit, 300);

            List<ExchangeClient.Kline> klines =
                    client().getKlines(symbol, timeframe, fetchLimit);

            long toMs = to.toEpochMilli();

            List<CandleProvider.Candle> filtered = new ArrayList<>();
            for (ExchangeClient.Kline k : klines) {
                if (k.openTime() <= toMs) {
                    filtered.add(new CandleProvider.Candle(
                            k.openTime(),
                            k.open(),
                            k.high(),
                            k.low(),
                            k.close(),
                            k.volume()
                    ));
                }
            }

            filtered.sort(Comparator.comparingLong(CandleProvider.Candle::time));

            // берём только последние limit штук
            if (filtered.size() > limit) {
                return filtered.subList(filtered.size() - limit, filtered.size());
            }

            return filtered;

        } catch (Exception e) {
            log.error("MarketService.loadMoreCandles({}, {}, {}, {}, {}) error",
                    chatId, symbol, timeframe, to, limit, e);
            return List.of();
        }
    }

    // -----------------------------------------------------
    // 4) % изменение цены
    // -----------------------------------------------------
    @Override
    public BigDecimal getChangePct(Long chatId, String symbol, String timeframe) {
        try {
            List<CandleProvider.Candle> list = loadCandles(chatId, symbol, timeframe, 50);
            if (list.size() < 2) {
                return BigDecimal.ZERO;
            }

            CandleProvider.Candle first = list.get(0);
            CandleProvider.Candle last = list.get(list.size() - 1);

            double f = first.close();
            double l = last.close();

            if (f == 0.0) {
                return BigDecimal.ZERO;
            }

            double pct = (l - f) / f * 100.0;
            return BigDecimal.valueOf(pct);

        } catch (Exception e) {
            log.error("MarketService.getChangePct({}, {}, {}) error",
                    chatId, symbol, timeframe, e);
            return BigDecimal.ZERO;
        }
    }
}
