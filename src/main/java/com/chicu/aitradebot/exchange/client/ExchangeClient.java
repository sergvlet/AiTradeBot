package com.chicu.aitradebot.exchange.client;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.exchange.model.Order;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 🌐 ExchangeClient — унифицированный интерфейс для всех бирж.
 *
 * 🧩 Реализуют:
 *   - BinanceExchangeClient
 *   - BybitExchangeClient
 *   - OkxExchangeClient
 *   - KucoinExchangeClient
 *
 * 🔹 Возможности:
 *   - Получение свечей (klines)
 *   - Получение текущей цены
 *   - Размещение ордеров (market / limit)
 *   - Получение балансов
 *   - Отмена ордеров
 */
public interface ExchangeClient {

    /**
     * Возвращает имя биржи ("BINANCE", "BYBIT", ...)
     */
    String getExchangeName();

    /**
     * Возвращает тип сети (MAINNET / TESTNET)
     */
    NetworkType getNetworkType();

    // ==================== 🔹 MARKET DATA ====================

    /**
     * Возвращает список свечей (klines) по символу.
     *
     * @param symbol    Торговая пара (BTCUSDT, ETHUSDT, ...)
     * @param interval  Таймфрейм ("1m", "1h", "4h", "1d", ...)
     * @param limit     Количество свечей
     */
    List<Kline> getKlines(String symbol, String interval, int limit) throws Exception;

    /**
     * Возвращает последнюю рыночную цену символа.
     */
    double getPrice(String symbol) throws Exception;

    // ==================== 🔹 ORDERS ====================

    /**
     * Размещает ордер (MARKET / LIMIT).
     *
     * @param chatId Пользователь (из БД)
     * @param symbol Торговая пара
     * @param side   BUY / SELL
     * @param type   MARKET / LIMIT
     * @param qty    Количество
     * @param price  Цена (для LIMIT)
     */
    OrderResult placeOrder(Long chatId, String symbol, String side, String type, double qty, Double price) throws Exception;

    /**
     * Размещает MARKET ордер в унифицированной форме.
     *
     * @param symbol Торговая пара
     * @param side   BUY / SELL
     * @param qty    Количество
     */
    Order placeMarketOrder(String symbol, OrderSide side, BigDecimal qty) throws Exception;

    /**
     * Отменяет активный ордер по ID.
     */
    boolean cancelOrder(Long chatId, String symbol, String orderId) throws Exception;

    // ==================== 🔹 BALANCE ====================

    /**
     * Возвращает баланс пользователя по конкретному активу.
     */
    Balance getBalance(Long chatId, String asset) throws Exception;

    /**
     * Возвращает все активные балансы пользователя (только активы с total > 0).
     */
    Map<String, Balance> getFullBalance(Long chatId) throws Exception;

    // ==================== 🔹 DTO ====================

    /**
     * DTO свечи.
     */
    record Kline(long openTime, double open, double high, double low, double close, double volume) {}

    /**
     * DTO результата ордера.
     */
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

    /**
     * DTO баланса.
     */
    record Balance(String asset, double free, double locked) {
        public double total() {
            return free + locked;
        }
    }
    /**
     * 📜 Получить список всех доступных торговых пар
     */
    List<String> getAllSymbols();

    /**
     * Возвращает список поддерживаемых таймфреймов для этой биржи.
     */
    default List<String> getAvailableTimeframes() {
        // По умолчанию — минимальный набор (для клиентов без реализации)
        return List.of("1m", "5m", "15m", "1h", "4h", "1d");
    }

}
