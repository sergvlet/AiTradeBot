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
import java.util.Objects;

/**
 * 🌐 ExchangeClient — унифицированный, STATELESS интерфейс биржи.
 *
 * Принципы (под прод):
 * 1) ❗ Клиент НЕ хранит network — сеть ВСЕГДА передаётся явно.
 * 2) ❗ MARKET поддерживает 2 схемы amount:
 *    - BASE_QTY  (base quantity)  — сколько базовой монеты купить/продать
 *    - QUOTE_QTY (quote amount)   — сколько котируемой монеты потратить (важно для BUY)
 * 3) extraParams — расширение без хардкода (timeInForce, clientOrderId, reduceOnly, etc.)
 * 4) ✅ Для SPOT-реконсайла (после рестартов): нужны методы getOpenOrders/getOrder/getMyTrades.
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
    List<Kline> getKlines(String symbol, String interval, int limit) throws Exception;

    /**
     * Диапазонные свечи (если биржа поддерживает start/end).
     * Fallback: старое поведение "последние limit".
     */
    default List<Kline> getKlines(String symbol, String interval, long startTimeMs, long endTimeMs, int limit) throws Exception {
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
     * quantity — ВСЕГДА BASE qty (кол-во базовой монеты).
     * price — используется для LIMIT (для MARKET обычно null).
     *
     * extraParams — механизм расширения:
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
     *  - BASE_QTY  => amount = base qty (BTC/ETH/...)
     *  - QUOTE_QTY => amount = quote amount (USDT/...) (важно для BUY)
     *
     * priceHint — необязательная подсказка текущей цены:
     *  - может использоваться для проверки minNotional и расчётов при QUOTE_QTY
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
     * OCO (SPOT). По умолчанию SELL.
     *
     * ⚠️ Если биржа не поддерживает OCO — обязана бросать UnsupportedOperationException,
     * чтобы ты не получил "тихий успех" и не потерял контроль над риском.
     */
    default OcoResult placeOcoOrder(
            Long chatId,
            NetworkType network,
            String symbol,
            BigDecimal quantityBase,
            BigDecimal takeProfitPrice,
            BigDecimal stopPrice,
            BigDecimal stopLimitPrice,
            Map<String, String> extraParams
    ) throws Exception {
        throw new UnsupportedOperationException(getExchangeName() + ": OCO not supported");
    }

    default OcoResult placeOcoOrder(
            Long chatId,
            NetworkType network,
            String symbol,
            BigDecimal quantityBase,
            BigDecimal takeProfitPrice,
            BigDecimal stopPrice,
            BigDecimal stopLimitPrice
    ) throws Exception {
        return placeOcoOrder(chatId, network, symbol, quantityBase, takeProfitPrice, stopPrice, stopLimitPrice, Map.of());
    }

    /**
     * Отмена ордера (по id биржи).
     */
    boolean cancelOrder(Long chatId, NetworkType network, String symbol, String orderId) throws Exception;

    // =====================================================================
    // ✅ SPOT RECONCILE (после рестарта)
    // =====================================================================

    /**
     * ✅ Получить открытые ордера по символу.
     * По умолчанию кидаем Unsupported — чтобы реконсайл не работал "втихую".
     */
    default List<OrderSnapshot> getOpenOrders(Long chatId, NetworkType network, String symbol) throws Exception {
        throw new UnsupportedOperationException(getExchangeName() + ": getOpenOrders not implemented");
    }

    /**
     * ✅ Получить состояние ордера по orderId (или clientOrderId — если поддерживается).
     */
    default OrderSnapshot getOrder(Long chatId, NetworkType network, String symbol, String orderIdOrClientOrderId) throws Exception {
        throw new UnsupportedOperationException(getExchangeName() + ": getOrder not implemented");
    }

    /**
     * ✅ Фактические сделки (fills) по символу.
     * Нужны, чтобы вычислять avgPrice/комиссию/реальное исполнение.
     */
    default List<TradeFill> getMyTrades(Long chatId, NetworkType network, String symbol, long startTimeMs, long endTimeMs, int limit) throws Exception {
        throw new UnsupportedOperationException(getExchangeName() + ": getMyTrades not implemented");
    }

    // =====================================================================
    // BALANCE (❗ network всегда явный)
    // =====================================================================

    Balance getBalance(Long chatId, String asset, NetworkType network) throws Exception;

    Map<String, Balance> getFullBalance(Long chatId, NetworkType network) throws Exception;

    // =====================================================================
    // SYMBOLS / INFO
    // =====================================================================

    List<String> getAllSymbols();

    default List<String> getAvailableTimeframes() {
        return List.of("1m", "5m", "15m", "1h", "4h", "1d");
    }

    AccountInfo getAccountInfo(long chatId, NetworkType network);

    /**
     * Комиссии аккаунта ВСЕГДА возвращаются в процентах: 0.1 = 0.1%, 0.06 = 0.06%, 0.18 = 0.18%.
     * Не rate/double fraction. Для перевода в долю используйте pct / 100.
     */
    AccountFees getAccountFees(long chatId, NetworkType network);

    List<SymbolDescriptor> getTradableSymbols(String quoteAsset);

    // =====================================================================
    // DTO
    // =====================================================================

    record Kline(long openTime, double open, double high, double low, double close, double volume) {}

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

    /**
     * ✅ Снимок ордера для реконсайла (реальное состояние с биржи).
     */
    record OrderSnapshot(
            String orderId,
            String clientOrderId,
            String symbol,
            String side,
            String type,
            String status,
            BigDecimal origQty,
            BigDecimal executedQty,
            BigDecimal price,
            BigDecimal avgPrice,
            long updateTimeMs
    ) {}

    /**
     * ✅ Сделка (fill) — нужна для вычисления комиссии, avgPrice, PnL и восстановления после рестарта.
     */
    record TradeFill(
            String tradeId,
            String orderId,
            String symbol,
            String side,
            BigDecimal price,
            BigDecimal qty,
            BigDecimal quoteQty,
            BigDecimal commission,
            String commissionAsset,
            long timeMs
    ) {}

    /**
     * ✅ Результат OCO (SPOT).
     */
    record OcoResult(
            String orderListId,
            String symbol,
            String status,
            String orderIdTp,
            String orderIdSl,
            long timestamp
    ) {}

    record Balance(String asset, double free, double locked) {
        public double total() { return free + locked; }

        public Balance {
            Objects.requireNonNull(asset, "asset");
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



