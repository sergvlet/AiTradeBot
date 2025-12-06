package com.chicu.aitradebot.strategy.core.impl;

import com.chicu.aitradebot.market.MarketStreamManager;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CandleProviderImpl implements CandleProvider {

    private final MarketStreamManager manager;

    @Override
    public List<CandleProvider.Candle> getRecentCandles(
            long chatId,
            String symbol,
            String timeframe,
            int limit
    ) {
        try {
            String sym = normalize(symbol);
            String tf  = normalize(timeframe);

            if (sym.isEmpty() || tf.isEmpty()) {
                log.warn("⚠ Пустой symbol/timeframe");
                return List.of();
            }

            List<com.chicu.aitradebot.market.model.Candle> raw =
                    manager.getCandles(sym, tf, limit);

            List<CandleProvider.Candle> list = new ArrayList<>();

            for (com.chicu.aitradebot.market.model.Candle c : raw) {
                list.add(new CandleProvider.Candle(
                        c.getTime(),
                        c.getOpen(),
                        c.getHigh(),
                        c.getLow(),
                        c.getClose(),
                        c.getVolume()
                ));
            }

            return list;

        } catch (Exception e) {
            log.error("❌ Ошибка получения свечей {} {}: {}", symbol, timeframe, e.getMessage());
            return List.of();
        }
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }
}
