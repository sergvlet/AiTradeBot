package com.chicu.aitradebot.market.stream;

import com.chicu.aitradebot.market.MarketPriceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketStreamRouter {

    private final MarketPriceService priceService;

    /**
     * Кэш допустимых источников:
     * symbol → BINANCE / BYBIT
     */
    private final Map<String, String> allowedExchange = new ConcurrentHashMap<>();

    /**
     * Разрешить символу получать данные только с выбранной биржи
     */
    public void allowSymbol(String symbol, String exchangeName) {
        if (symbol == null || exchangeName == null) return;
        allowedExchange.put(symbol.toUpperCase(), exchangeName.toUpperCase());
        log.info("✅ Разрешён стрим: {} @ {}", symbol, exchangeName);
    }

    /**
     * Универсальная точка входа для любого тика
     */
    public void route(Tick tick) {
        if (tick == null) return;

        String symbol = normalize(tick.symbol());
        if (symbol.isEmpty()) return;

        // фильтрация по источнику
        if (!isAllowed(symbol, tick.exchange())) {
            //log.debug("⛔ Отфильтрован тик {} от {}", symbol, tick.exchange());
            return;
        }

        BigDecimal price = tick.price();
        if (price == null || price.signum() <= 0) return;

        // сохраняем цену
        priceService.updatePrice(symbol, price);

        log.debug("💹 [{}] {} = {}", tick.exchange(), symbol, price);
    }

    private boolean isAllowed(String symbol, String exchangeFromTick) {
        String allowed = allowedExchange.get(symbol);
        if (allowed == null) {
            // если не настроено — блокируем ВСЁ, чтобы не было диких данных
            return false;
        }
        return allowed.equalsIgnoreCase(exchangeFromTick);
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }
}
