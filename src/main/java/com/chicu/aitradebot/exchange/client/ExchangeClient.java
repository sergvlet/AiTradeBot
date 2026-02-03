package com.chicu.aitradebot.exchange.client;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.exchange.model.AccountFees;
import com.chicu.aitradebot.exchange.model.AccountInfo;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.market.model.SymbolDescriptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 🌐 ExchangeClient — унифицированный, STATELESS интерфейс биржи.
 *
 * Принципы (под прод):
 * 1) ❗ Клиент НЕ хранит network, сеть ВСЕГДА передаётся явно.
 * 2) ❗ Метод placeMarketOrder поддерживает две схемы количества:
 *    - BASE_QTY  (base quantity)  — сколько базовой монеты купить/продать
 *    - QUOTE_QTY (quote amount)   — сколько котируемой монеты потратить (особенно важно для BUY)
 * 3) extraParams — расширение без хардкода (timeInForce, clientOrderId, reduceOnly, etc.)
 *
 * Реализации:
 *  - BinanceExchangeClient
 *  - BybitExchangeClient
 *  - OkxExchangeClient
 */
public interface ExchangeClient {

    // =====================================================================
    // META
    // =====================================================================

    /**
     * Имя биржи ("BINANCE", "BYBIT", ...).
     */
    String getExchangeName();

    // =====================================================================
    // MARKET DATA
    // =====================================================================

    /**
     * Получение свечей (REST). Публичные ручки обычно не зависят от ключей.
     */
    List<Kline> getKlines(
            String symbol,
            String interval,
            int limit
    ) throws Exception;

    /**
     * Диапазонные свечи (если биржа поддерживает start/end).
     * Fallback: старое поведение "последние limit".
     */
    default List<Kline> getKlines(
            String symbol,
            String interval,
            long startTimeMs,
            long endTimeMs,
            int limit
    ) throws Exception {
        return getKlines(symbol, interval, limit);
    }

    /**
     * Последняя цена (REST).
     */
    double getPrice(String symbol) throws Exception;

    // =====================================================================
    // ORDERS (❗ network всегда явный)
    // =====================================================================

    /**
     * Универсальный ордер.
     *
     * quantity — ВСЕГДА BASE qty (кол-во базовой монеты), как принято у большинства бирж.
     * price — используется для LIMIT (для MARKET обычно null).
     *
     * extraParams — безопасный механизм расширения без хардкода:
     *  - Binance: timeInForce, quoteOrderQty, newClientOrderId, etc.
     *  - Bybit:  timeInForce, orderLinkId, etc.
     */
    OrderResult placeOrder(
            Long chatId,
            NetworkType network,
            String symbol,
            String side,
            String type,
            BigDecimal quantity,
            BigDecimal price,
            Map<String, String> extraParams
    ) throws Exception;

    /**
     * Упрощённый overload: без extraParams.
     */
    default OrderResult placeOrder(
            Long chatId,
            NetworkType network,
            String symbol,
            String side,
            String type,
            BigDecimal quantity,
            BigDecimal price
    ) throws Exception {
        return placeOrder(chatId, network, symbol, side, type, quantity, price, Map.of());
    }

    /**
     * MARKET ордер.
     *
     * amountType:
     *  - BASE_QTY  => amount = base quantity (BTC/ETH/...)
     *  - QUOTE_QTY => amount = quote amount (USDT/...) (особенно важно для BUY)
     *
     * priceHint — необязательная подсказка текущей цены:
     *  - может использоваться для валидации minNotional / фильтров / расчётов при QUOTE_QTY
     *  - если не передан — реализация может сама получить цену
     */
    Order placeMarketOrder(
            Long chatId,
            NetworkType network,
            String symbol,
            OrderSide side,
            BigDecimal amount,
            OrderAmountType amountType,
            BigDecimal priceHint
    ) throws Exception;

    /**
     * Упрощённый overload: без priceHint.
     */
    default Order placeMarketOrder(
            Long chatId,
            NetworkType network,
            String symbol,
            OrderSide side,
            BigDecimal amount,
            OrderAmountType amountType
    ) throws Exception {
        return placeMarketOrder(chatId, network, symbol, side, amount, amountType, null);
    }

    /**
     * Отмена ордера.
     */
    boolean cancelOrder(
            Long chatId,
            NetworkType network,
            String symbol,
            String orderId
    ) throws Exception;

    // =====================================================================
    // BALANCE (❗ network всегда явный)
    // =====================================================================

    Balance getBalance(
            Long chatId,
            String asset,
            NetworkType network
    ) throws Exception;

    Map<String, Balance> getFullBalance(
            Long chatId,
            NetworkType network
    ) throws Exception;

    // =====================================================================
    // SYMBOLS / INFO
    // =====================================================================

    List<String> getAllSymbols();

    default List<String> getAvailableTimeframes() {
        return List.of("1m", "5m", "15m", "1h", "4h", "1d");
    }

    AccountInfo getAccountInfo(
            long chatId,
            NetworkType network
    );

    AccountFees getAccountFees(
            long chatId,
            NetworkType network
    );

    List<SymbolDescriptor> getTradableSymbols(String quoteAsset);

    // =====================================================================
    // DTO
    // =====================================================================

    record Kline(
            long openTime,
            double open,
            double high,
            double low,
            double close,
            double volume
    ) {}

    /**
     * Единый DTO результата ордера.
     *
     * qty:
     *  - для BUY/SELL MARKET лучше возвращать исполненный BASE qty (executed qty),
     *    даже если вход был через QUOTE_QTY.
     *
     * price:
     *  - средняя/фактическая цена исполнения (если биржа вернула),
     *    иначе допускается null.
     */
    record OrderResult(
            String orderId,
            String symbol,
            String side,
            String type,
            BigDecimal qty,
            BigDecimal price,
            String status,
            long timestamp
    ) {}

    record Balance(String asset, double free, double locked) {
        public double total() {
            return free + locked;
        }
    }

    /**
     * Тип количества для MARKET (чтобы не путать BUY через quoteAmount и SELL через baseQty).
     */
    enum OrderAmountType {
        BASE_QTY,
        QUOTE_QTY
    }
}
