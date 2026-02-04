package com.chicu.aitradebot.market.stream;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.exchange.binance.BinanceMarketStreamAdapter;
import com.chicu.aitradebot.exchange.bybit.BybitMarketStreamAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Универсальный менеджер WebSocket-подписок.
 *
 * ПРАВИЛО:
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
     * (exchange, network, chatId) -> symbol
     */
    private final Map<ConnKey, String> currentSymbol = new ConcurrentHashMap<>();

    /**
     * Lock per ConnKey (чтобы не блокировать все чаты одновременно)
     */
    private final Map<ConnKey, Object> locks = new ConcurrentHashMap<>();

    private Object lockFor(ConnKey key) {
        return locks.computeIfAbsent(key, k -> new Object());
    }

    // ============================================================
    // Legacy method (compile only)
    // ============================================================

    /**
     * Старый метод оставляем только чтобы компилилось.
     * ✅ БОЛЬШЕ НИЧЕГО НЕ ДЕЛАЕТ.
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
    }

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Новый безопасный метод.
     */
    public void subscribeSymbol(String exchangeName, NetworkType networkType, long chatId, String rawSymbol) {

        if (exchangeName == null || exchangeName.isBlank()) {
            log.error("❌ subscribeSymbol: exchangeName == null/blank → ПРОПУСК");
            return;
        }
        if (networkType == null) {
            log.error("❌ subscribeSymbol: networkType == null → ПРОПУСК");
            return;
        }
        if (chatId <= 0) {
            log.error("❌ subscribeSymbol: chatId <= 0 → ПРОПУСК (chatId={})", chatId);
            return;
        }

        String symbol = normalizeSymbol(rawSymbol);
        if (symbol.isEmpty()) {
            log.warn("⚠ subscribeSymbol: пустой символ → ПРОПУСК (chatId={}, ex={}, net={})",
                    chatId, exchangeName, networkType);
            return;
        }

        String ex = exchangeName.trim().toUpperCase(Locale.ROOT);
        ConnKey key = new ConnKey(ex, networkType, chatId);

        synchronized (lockFor(key)) {
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
    }

    /**
     * Отписать текущий символ для конкретного контекста.
     */
    public void unsubscribeCurrent(String exchangeName, NetworkType networkType, long chatId) {
        if (exchangeName == null || exchangeName.isBlank() || networkType == null || chatId <= 0) return;

        String ex = exchangeName.trim().toUpperCase(Locale.ROOT);
        ConnKey key = new ConnKey(ex, networkType, chatId);

        synchronized (lockFor(key)) {
            String prev = currentSymbol.remove(key);
            if (prev == null) return;

            log.info("🧹 unsubscribeCurrent(chatId={}, ex={}, net={}, symbol={})", chatId, ex, networkType, prev);

            switch (ex) {
                case "BINANCE" -> safeUnsubscribeBinance(networkType, prev);
                case "BYBIT"   -> safeUnsubscribeBybit(prev);
                default -> { /* ignore */ }
            }
        }
    }

    // ============================================================
    // BINANCE
    // ============================================================

    /**
     * ✅ Лучший порядок:
     * 1) subscribe(next)
     * 2) если успешно → unsubscribe(prev)
     * 3) currentSymbol = next
     */
    private void switchSymbolBinance(ConnKey key, String prevSymbol, String nextSymbol) {

        boolean subscribed = false;

        try {
            // ✅ поддерживаем оба варианта сигнатур:
            // - subscribeTicker(NetworkType, String)
            // - subscribeTicker(String)
            subscribed = invokeBinanceSubscribe(key.network(), nextSymbol);

            if (subscribed) {
                currentSymbol.put(key, nextSymbol);
                log.info("✅ Binance WS subscribed → {} (chatId={}, net={})", nextSymbol, key.chatId(), key.network());
            } else {
                log.error("❌ Binance subscribe skipped (no matching method) chatId={} net={} symbol={}",
                        key.chatId(), key.network(), nextSymbol);
                return;
            }
        } catch (Exception ex) {
            log.error("❌ Binance subscribe error (chatId={}, net={}, symbol={}): {}",
                    key.chatId(), key.network(), nextSymbol, ex.getMessage(), ex);
            return;
        }

        // ✅ только после успешной подписки — отписываем прошлый
        if (prevSymbol != null && !Objects.equals(prevSymbol, nextSymbol)) {
            safeUnsubscribeBinance(key.network(), prevSymbol);
        }
    }

    private void safeUnsubscribeBinance(NetworkType net, String symbol) {
        try {
            boolean ok = invokeBinanceUnsubscribe(net, symbol);
            if (ok) {
                log.info("🔌 Binance unsubscribed: {} (net={})", symbol, net);
            } else {
                log.warn("⚠ Binance unsubscribe skipped (no matching method) net={} symbol={}", net, symbol);
            }
        } catch (Exception ex) {
            log.warn("⚠ Binance unsubscribe error: {}", ex.getMessage());
        }
    }

    /**
     * Reflection-адаптер, чтобы класс компилился при любой сигнатуре адаптера Binance.
     */
    private boolean invokeBinanceSubscribe(NetworkType net, String symbol) throws Exception {
        // 1) subscribeTicker(NetworkType, String)
        Method m1 = findMethod(binance.getClass(), "subscribeTicker", NetworkType.class, String.class);
        if (m1 != null) {
            m1.invoke(binance, net, symbol);
            return true;
        }
        // 2) subscribeTicker(String)
        Method m2 = findMethod(binance.getClass(), "subscribeTicker", String.class);
        if (m2 != null) {
            m2.invoke(binance, symbol);
            return true;
        }
        return false;
    }

    private boolean invokeBinanceUnsubscribe(NetworkType net, String symbol) throws Exception {
        // 1) unsubscribeTicker(NetworkType, String)
        Method m1 = findMethod(binance.getClass(), "unsubscribeTicker", NetworkType.class, String.class);
        if (m1 != null) {
            m1.invoke(binance, net, symbol);
            return true;
        }
        // 2) unsubscribeTicker(String)
        Method m2 = findMethod(binance.getClass(), "unsubscribeTicker", String.class);
        if (m2 != null) {
            m2.invoke(binance, symbol);
            return true;
        }
        return false;
    }

    // ============================================================
    // BYBIT
    // ============================================================

    /**
     * BybitMarketStreamAdapter сейчас ticker-only и без network overload.
     * В контексте мы network держим для чистоты, но вызов идёт по symbol.
     *
     * ✅ Лучший порядок:
     * 1) subscribe(next)
     * 2) currentSymbol = next
     * 3) unsubscribe(prev)
     */
    private void switchSymbolBybit(ConnKey key, String prevSymbol, String nextSymbol) {

        try {
            bybit.subscribeTicker(nextSymbol);
            currentSymbol.put(key, nextSymbol);
            log.info("✅ Bybit WS subscribed → {} (chatId={}, net={})", nextSymbol, key.chatId(), key.network());
        } catch (Exception ex) {
            log.error("❌ Bybit subscribe error (chatId={}, net={}, symbol={}): {}",
                    key.chatId(), key.network(), nextSymbol, ex.getMessage(), ex);
            return;
        }

        if (prevSymbol != null && !Objects.equals(prevSymbol, nextSymbol)) {
            safeUnsubscribeBybit(prevSymbol);
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

    // ============================================================
    // HELPERS
    // ============================================================

    private String normalizeSymbol(String s) {
        if (s == null) return "";
        return s.trim().replace("/", "").toUpperCase(Locale.ROOT);
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            return cls.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
