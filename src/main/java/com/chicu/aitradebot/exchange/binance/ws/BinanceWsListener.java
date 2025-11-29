package com.chicu.aitradebot.exchange.binance.ws;

/**
 * Слушатель входящих сообщений от Binance WS.
 * Используется CandleWebSocketHandler и всеми realtime сервисами.
 */
@FunctionalInterface
public interface BinanceWsListener {

    /**
     * Вызывается для КАЖДОГО сообщения WebSocket.
     *
     * @param json JSON строка от Binance.
     */
    void onMessage(String json);
}
