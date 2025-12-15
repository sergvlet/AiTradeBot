package com.chicu.aitradebot.market.stream;

import com.chicu.aitradebot.market.MarketPriceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketStreamRouter {

    private final MarketPriceService priceService;

    /**
     * symbol → BINANCE / BYBIT
     * Если карта пустая — пропускаются ВСЕ источники (режим по умолчанию).
     */
    private final Map<String, String> allowedExchange = new ConcurrentHashMap<>();


    /**
     * Включает фильтрацию и привязывает символ к бирже
     */
    public void allowSymbol(String symbol, String exchangeName) {
        if (symbol == null || exchangeName == null) return;
        allowedExchange.put(symbol.toUpperCase(), exchangeName.toUpperCase());
        log.info("✅ Разрешён стрим: {} @ {}", symbol, exchangeName);
    }


    /**
     * Основной роутер
     */
    public void route(Tick tick) {
        if (tick == null) return;

        String symbol = normalize(tick.symbol());
        if (symbol.isEmpty()) return;

        // ФИЛЬТРАЦИЯ ТИКОВ
        if (!isAllowed(symbol, tick.exchange())) {
            return;
        }

        BigDecimal price = tick.price();
        if (price == null || price.signum() <= 0) return;

        priceService.updatePrice(symbol, price);

        //log.debug("💹 [{}] {} = {}", tick.exchange(), symbol, price);
    }


    /**
     * Логика допуска источников
     * 1) Если allowedExchange пустой → пропускаем всё
     * 2) Если символ есть в карте → пропускаем только подходящую биржу
     * 3) Если символа нет в карте → пропускаем (символ не отфильтрован)
     */
    private boolean isAllowed(String symbol, String exchangeFromTick) {

        // 1) Если пользователь НЕ настроил фильтрацию — разрешаем ВСЁ
        if (allowedExchange.isEmpty()) {
            return true;
        }

        // 2) Пользователь ограничил именно этот символ?
        String allowed = allowedExchange.get(symbol);

        if (allowed == null) {
            // Фильтрация включена, но символ не указан → В ЭТОМ случае допускаем цену
            return true;
        }

        // 3) Символ найден → пропускаем только правильную биржу
        return allowed.equalsIgnoreCase(exchangeFromTick);
    }


    private String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }
}
