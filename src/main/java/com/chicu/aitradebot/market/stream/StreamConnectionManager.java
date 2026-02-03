package com.chicu.aitradebot.market.stream;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.exchange.binance.BinanceMarketStreamAdapter;
import com.chicu.aitradebot.exchange.bybit.BybitMarketStreamAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Универсальный менеджер WebSocket-подписок.
 *
 * ГЛАВНОЕ ПРАВИЛО:
 * - Подписка ВСЕГДА должна быть в контексте (exchange + network + chatId).
 * - Никаких chatId=-1 и network=null.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamConnectionManager {

    private final BinanceMarketStreamAdapter binance;
    private final BybitMarketStreamAdapter bybit;

    private record ConnKey(String exchange, NetworkType network, long chatId) {}

    /**
     * Текущий подписанный символ на ключ подключения.
     * Ключ: (exchange, network, chatId) -> symbol
     */
    private final Map<ConnKey, String> currentSymbol = new ConcurrentHashMap<>();

    /**
     * Старый метод оставляем только чтобы компилилось.
     * ✅ БОЛЬШЕ НИЧЕГО НЕ ДЕЛАЕТ, чтобы не было смешения потоков.
     */
    @Deprecated
    public void subscribeSymbol(String exchangeName, String rawSymbol) {
        String ex = (exchangeName == null) ? "null" : exchangeName.trim().toUpperCase(Locale.ROOT);
        String sym = normalizeSymbol(rawSymbol);

        log.error(
                "❌ subscribeSymbol(exchange,symbol) запрещён: нет chatId/network → будет смешение потоков. " +
                "Вызов проигнорирован. ex={} symbol={}",
                ex, sym
        );
        // НИЧЕГО НЕ ДЕЛАЕМ!
    }

    /**
     * Новый безопасный метод.
     */
    public void subscribeSymbol(String exchangeName, NetworkType networkType, long chatId, String rawSymbol) {

        if (exchangeName == null || exchangeName.isBlank()) {
            log.error("❌ subscribeSymbol: exchangeName == null/blank → ПРОПУСК");
            return;
        }
        if (networkType == null) {
            log.error("❌ subscribeSymbol: networkType == null → ПРОПУСК (нельзя подписываться без сети)");
            return;
        }
        if (chatId <= 0) {
            log.error("❌ subscribeSymbol: chatId <= 0 → ПРОПУСК (chatId={})", chatId);
            return;
        }

        String symbol = normalizeSymbol(rawSymbol);
        if (symbol.isEmpty()) {
            log.warn("⚠ subscribeSymbol: пустой символ → ПРОПУСК (chatId={}, ex={}, net={})", chatId, exchangeName, networkType);
            return;
        }

        String ex = exchangeName.trim().toUpperCase(Locale.ROOT);
        ConnKey key = new ConnKey(ex, networkType, chatId);

        String prev = currentSymbol.get(key);
        if (symbol.equals(prev)) {
            log.debug("⏭ Уже подписаны: chatId={} ex={} net={} symbol={}", chatId, ex, networkType, symbol);
            return;
        }

        log.info("📡 subscribeSymbol(chatId={}, ex={}, net={}, symbol={}, prev={})",
                chatId, ex, networkType, symbol, prev);

        switch (ex) {
            case "BINANCE" -> switchSymbolBinance(key, prev, symbol);
            case "BYBIT"   -> switchSymbolBybit(key, prev, symbol);
            default -> log.warn("⚠ Неизвестная биржа '{}': подписка пропущена (chatId={}, net={}, symbol={})",
                    ex, chatId, networkType, symbol);
        }
    }

    /**
     * Отписать текущий символ для конкретного контекста (exchange+network+chatId).
     * Удобно при остановке стратегии.
     */
    public void unsubscribeCurrent(String exchangeName, NetworkType networkType, long chatId) {
        if (exchangeName == null || exchangeName.isBlank() || networkType == null || chatId <= 0) return;

        String ex = exchangeName.trim().toUpperCase(Locale.ROOT);
        ConnKey key = new ConnKey(ex, networkType, chatId);

        String prev = currentSymbol.remove(key);
        if (prev == null) return;

        log.info("🧹 unsubscribeCurrent(chatId={}, ex={}, net={}, symbol={})", chatId, ex, networkType, prev);

        switch (ex) {
            case "BINANCE" -> safeUnsubscribeBinance(prev);
            case "BYBIT"   -> safeUnsubscribeBybit(prev);
            default -> { /* ignore */ }
        }
    }

    // =====================================================================
    // BINANCE
    // =====================================================================

    private synchronized void switchSymbolBinance(ConnKey key, String prevSymbol, String nextSymbol) {
        ensureBinanceConnected();

        if (prevSymbol != null && !prevSymbol.equals(nextSymbol)) {
            safeUnsubscribeBinance(prevSymbol);
        }

        try {
            binance.subscribeTicker(nextSymbol);
            currentSymbol.put(key, nextSymbol);
            log.info("✅ Binance WS subscribed → {} (chatId={}, net={})", nextSymbol, key.chatId(), key.network());
        } catch (Exception ex) {
            log.error("❌ Binance subscribe error (chatId={}, net={}, symbol={}): {}",
                    key.chatId(), key.network(), nextSymbol, ex.getMessage(), ex);
        }
    }

    private void safeUnsubscribeBinance(String symbol) {
        try {
            binance.unsubscribeTicker(symbol);
            log.info("🔌 Binance unsubscribed: {}", symbol);
        } catch (Exception ex) {
            log.warn("⚠ Binance unsubscribe error: {}", ex.getMessage());
        }
    }

    private void ensureBinanceConnected() {
        try {
            if (!binance.isConnected()) {
                binance.connect();
                log.info("🔌 Binance WS подключён (lazy connect)");
            }
        } catch (Exception ex) {
            log.error("❌ Binance connect error: {}", ex.getMessage(), ex);
        }
    }

    // =====================================================================
    // BYBIT
    // =====================================================================

    private synchronized void switchSymbolBybit(ConnKey key, String prevSymbol, String nextSymbol) {
        ensureBybitConnected();

        if (prevSymbol != null && !prevSymbol.equals(nextSymbol)) {
            safeUnsubscribeBybit(prevSymbol);
        }

        try {
            bybit.subscribeTicker(nextSymbol);
            currentSymbol.put(key, nextSymbol);
            log.info("✅ Bybit WS subscribed → {} (chatId={}, net={})", nextSymbol, key.chatId(), key.network());
        } catch (Exception ex) {
            log.error("❌ Bybit subscribe error (chatId={}, net={}, symbol={}): {}",
                    key.chatId(), key.network(), nextSymbol, ex.getMessage(), ex);
        }
    }

    private void safeUnsubscribeBybit(String symbol) {
        try {
            bybit.unsubscribeTicker(symbol);
            log.info("🔌 Bybit unsubscribed: {}", symbol);
        } catch (Exception ex) {
            log.warn("⚠ Bybit unsubscribe error: {}", ex.getMessage());
        }
    }

    private void ensureBybitConnected() {
        try {
            if (!bybit.isConnected()) {
                bybit.connect();
                log.info("🔌 Bybit WS подключён (lazy connect)");
            }
        } catch (Exception ex) {
            log.error("❌ Bybit connect error: {}", ex.getMessage(), ex);
        }
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private String normalizeSymbol(String s) {
        if (s == null) return "";
        return s.trim().replace("/", "").toUpperCase(Locale.ROOT);
    }
}
