package com.chicu.aitradebot.market.stream;

import com.chicu.aitradebot.market.MarketPriceService;
import com.chicu.aitradebot.market.model.UnifiedKline;
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
     * symbol -> exchangeName
     *
     * Если карта пустая — фильтрация отключена (пропускаем всё).
     * Если символ есть в карте — пропускаем события только от указанной биржи.
     */
    private final Map<String, String> allowedExchange = new ConcurrentHashMap<>();

    /**
     * Включает фильтрацию и привязывает символ к бирже.
     */
    public void allowSymbol(String symbol, String exchangeName) {
        String sym = normalize(symbol);
        String ex  = normalize(exchangeName);

        if (sym.isEmpty() || ex.isEmpty()) return;

        allowedExchange.put(sym, ex);
        log.info("✅ Разрешён стрим: {} @ {}", sym, ex);
    }

    /**
     * (Опционально) убрать ограничение по символу.
     */
    public void disallowSymbol(String symbol) {
        String sym = normalize(symbol);
        if (sym.isEmpty()) return;

        allowedExchange.remove(sym);
        log.info("🧹 Фильтр снят: {}", sym);
    }

    /**
     * Основной роутер тиков.
     */
    public void route(Tick tick) {
        if (tick == null) return;

        String symbol = normalize(tick.symbol());
        if (symbol.isEmpty()) return;

        String exchangeFromEvent = normalize(tick.exchange());
        if (!isAllowed(symbol, exchangeFromEvent)) return;

        BigDecimal price = tick.price();
        if (!isValidPrice(price)) return;

        priceService.updatePrice(symbol, price);
        // log.debug("💹 [{}] {} = {}", exchangeFromEvent, symbol, price);
    }

    /**
     * Роутер KLINE (универсальный).
     * Цена берётся из close (самая логичная для "текущей" цены на UI/логике).
     */
    public void routeKline(String exchangeName, UnifiedKline kline) {
        if (kline == null) return;

        String symbol = normalize(kline.getSymbol());
        if (symbol.isEmpty()) return;

        String exchangeFromEvent = normalize(exchangeName);
        if (!isAllowed(symbol, exchangeFromEvent)) return;

        BigDecimal close = kline.getClose();
        if (!isValidPrice(close)) return;

        priceService.updatePrice(symbol, close);
        // log.debug("🕯 [{}] {} close={}", exchangeFromEvent, symbol, close);
    }

    /**
     * Логика допуска источников:
     * 1) Если allowedExchange пустой → пропускаем всё
     * 2) Если символ есть в карте → пропускаем только подходящую биржу
     * 3) Если символа нет в карте → пропускаем (символ не ограничен)
     */
    private boolean isAllowed(String symbol, String exchangeFromEvent) {

        if (allowedExchange.isEmpty()) {
            return true;
        }

        String allowed = allowedExchange.get(symbol);
        if (allowed == null) {
            return true;
        }

        return allowed.equalsIgnoreCase(exchangeFromEvent);
    }

    private boolean isValidPrice(BigDecimal price) {
        return price != null && price.signum() > 0;
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }
}
