package com.chicu.aitradebot.strategy.smartfusion.components;

import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SmartFusionVolatilityShield
 *
 * Блокирует сделки при слишком резком изменении цены.
 * Используется RiskManager и SmartFusionStrategy до открытия позиции.
 *
 * ⚙️ Пример логики:
 *   если за последние 5 минут изменение цены > 2% — вход блокируется.
 */
@Component
@Slf4j
public class SmartFusionVolatilityShield {

    /** chatId|symbol → очередь последних цен */
    private final Map<String, Deque<PricePoint>> priceHistory = new ConcurrentHashMap<>();

    /** Проверяет, допустима ли волатильность */
    public boolean allowTrade(long chatId, String symbol, double currentPrice, SmartFusionStrategySettings cfg) {
        String key = chatId + "|" + symbol;
        Deque<PricePoint> queue = priceHistory.computeIfAbsent(key, x -> new LinkedList<>());

        Instant now = Instant.now();
        queue.addLast(new PricePoint(now, currentPrice));

        // удалить старые точки
        queue.removeIf(p -> p.timestamp.isBefore(now.minusSeconds(cfg.getVolatilityWindowSec())));

        if (queue.size() < 2) return true;

        double first = queue.getFirst().price;
        double changePct = Math.abs((currentPrice - first) / first * 100.0);

        boolean ok = changePct < cfg.getVolatilityThresholdPct();

        if (!ok) {
            log.warn("⚠️ VolatilityShield блокирует вход {}: скачок {}% за {} сек (> {}%)",
                    symbol, round(changePct), cfg.getVolatilityWindowSec(), cfg.getVolatilityThresholdPct());
        } else {
            log.debug("✅ VolatilityShield OK {} ({}% < {}%)", symbol,
                    round(changePct), cfg.getVolatilityThresholdPct());
        }

        return ok;
    }

    /** Очистка истории при остановке стратегии */
    public void clear(long chatId, String symbol) {
        priceHistory.remove(chatId + "|" + symbol);
    }

    private record PricePoint(Instant timestamp, double price) {}

    private static double round(double v) {
        return new BigDecimal(v).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }
}
