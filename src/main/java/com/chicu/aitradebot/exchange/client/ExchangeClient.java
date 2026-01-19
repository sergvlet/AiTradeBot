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

 * ❗ Клиент НЕ хранит network.
 * ❗ Network ВСЕГДА передаётся явно.

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
     * Получение свечей (REST).
     */
    List<Kline> getKlines(
            String symbol,
            String interval,
            int limit
    ) throws Exception;

    // в ExchangeClient
    default List<Kline> getKlines(
            String symbol,
            String interval,
            long startTimeMs,
            long endTimeMs,
            int limit
    ) throws Exception {
        // fallback: старое поведение "последние limit"
        return getKlines(symbol, interval, limit);
    }


    /**
     * Последняя цена (REST).
     */
    double getPrice(String symbol) throws Exception;

    // =====================================================================
    // ORDERS
    // =====================================================================

    /**
     * Универсальный ордер.
     */
    OrderResult placeOrder(
            Long chatId,
            String symbol,
            String side,
            String type,
            double qty,
            Double price
    ) throws Exception;

    /**
     * MARKET ордер.
     */
    Order placeMarketOrder(
            String symbol,
            OrderSide side,
            BigDecimal qty
    ) throws Exception;

    /**
     * Отмена ордера.
     */
    boolean cancelOrder(
            Long chatId,
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

    record OrderResult(
            String orderId,
            String symbol,
            String side,
            String type,
            double qty,
            double price,
            String status,
            long timestamp
    ) {}

    record Balance(String asset, double free, double locked) {
        public double total() {
            return free + locked;
        }
    }
}
