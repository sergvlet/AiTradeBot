package com.chicu.aitradebot.market.stream;

import com.chicu.aitradebot.exchange.binance.BinanceMarketStreamAdapter;
import com.chicu.aitradebot.exchange.bybit.BybitMarketStreamAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Универсальный менеджер WebSocket-подписок.
 * Теперь полностью безопасен: exchangeName никогда не вызовет NPE.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamConnectionManager {

    private final BinanceMarketStreamAdapter binance;
    private final BybitMarketStreamAdapter bybit;

    private String currentBinanceSymbol = null;
    private String currentBybitSymbol = null;

    /**
     * Главная точка входа.
     * Оркестратор вызывает:
     *     subscribeSymbol(exchangeName, symbol)
     *
     * Мы приводим к безопасному состоянию и подписываем WS.
     */
    public synchronized void subscribeSymbol(String exchangeName, String rawSymbol) {

        if (exchangeName == null || exchangeName.isBlank()) {
            log.error("❌ subscribeSymbol: exchangeName == null → ПРОПУСК ПОДПИСКИ");
            return;
        }

        String symbol = normalizeSymbol(rawSymbol);
        if (symbol.isEmpty()) {
            log.warn("⚠ subscribeSymbol: пустой символ, отказ");
            return;
        }

        String ex = exchangeName.trim().toUpperCase();

        log.info("📡 subscribeSymbol(exchange={}, symbol={})", ex, symbol);

        switch (ex) {
            case "BINANCE" -> subscribeBinance(symbol);
            case "BYBIT"   -> subscribeBybit(symbol);
            default -> log.warn("⚠ Неизвестная биржа '{}', символ '{}' пропущен", ex, symbol);
        }
    }

    // =====================================================================
    // BINANCE
    // =====================================================================

    private void subscribeBinance(String symbol) {
        ensureBinanceConnected();

        // если другой символ был подписан -> отписываем
        if (currentBinanceSymbol != null && !currentBinanceSymbol.equals(symbol)) {
            safeUnsubscribeBinance(currentBinanceSymbol);
        }

        try {
            binance.subscribeTicker(symbol);
            currentBinanceSymbol = symbol;
            log.info("✅ Binance WS subscribed → {}", symbol);
        } catch (Exception ex) {
            log.error("❌ Binance subscribe error: {}", ex.getMessage());
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
            log.error("❌ Binance connect error: {}", ex.getMessage());
        }
    }

    // =====================================================================
    // BYBIT
    // =====================================================================

    private void subscribeBybit(String symbol) {
        ensureBybitConnected();

        if (currentBybitSymbol != null && !currentBybitSymbol.equals(symbol)) {
            safeUnsubscribeBybit(currentBybitSymbol);
        }

        try {
            bybit.subscribeTicker(symbol);
            currentBybitSymbol = symbol;
            log.info("✅ Bybit WS subscribed → {}", symbol);
        } catch (Exception ex) {
            log.error("❌ Bybit subscribe error: {}", ex.getMessage());
        }
    }

    private void safeUnsubscribeBybit(String symbol) {
        try {
            bybit.unsubscribeTicker(symbol);
            log.info("🔌 Bybit unsubscribed: {}", symbol);
        } catch (Exception ignored) {}
    }

    private void ensureBybitConnected() {
        try {
            if (!bybit.isConnected()) {
                bybit.connect();
                log.info("🔌 Bybit WS подключён (lazy connect)");
            }
        } catch (Exception ex) {
            log.error("❌ Bybit connect error: {}", ex.getMessage());
        }
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private String normalizeSymbol(String s) {
        if (s == null) return "";
        return s.trim().replace("/", "").toUpperCase();
    }
}
