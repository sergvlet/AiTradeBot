package com.chicu.aitradebot.market.stream;

import com.chicu.aitradebot.exchange.binance.BinanceMarketStreamAdapter;
import com.chicu.aitradebot.exchange.bybit.BybitMarketStreamAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StreamConnectionManager {

    private final BinanceMarketStreamAdapter binance;
    private final BybitMarketStreamAdapter bybit;

    // ⭐ текущие активные подписки
    private String currentBinanceSymbol = null;
    private String currentBybitSymbol = null;

    /**
     * Главная точка входа:
     * Стратегия вызывает → подключаем WS → подписываемся на нужную пару.
     */
    public synchronized void subscribeSymbol(String rawSymbol, String exchangeName) {

        String symbol = normalizeSymbol(rawSymbol);

        if (symbol.isEmpty()) {
            log.warn("⚠ Пустой символ — пропускаем подписку");
            return;
        }

        switch (exchangeName.toUpperCase()) {
            case "BINANCE" -> subscribeBinance(symbol);
            case "BYBIT"   -> subscribeBybit(symbol);
            default ->
                    log.warn("⚠ Неизвестная биржа: {}", exchangeName);
        }
    }

    // =====================================================================
    // BINANCE
    // =====================================================================

    private void subscribeBinance(String symbol) {

        ensureBinanceConnected();

        // отписка от предыдущего символа
        if (currentBinanceSymbol != null && !currentBinanceSymbol.equals(symbol)) {
            try {
                binance.unsubscribeTicker(currentBinanceSymbol);
                log.info("🔌 Binance unsubscribed: {}", currentBinanceSymbol);
            } catch (Exception ex) {
                log.warn("⚠ Ошибка Binance unsubscribe: {}", ex.getMessage());
            }
        }

        // подписка на новый символ
        try {
            binance.subscribeTicker(symbol);
            currentBinanceSymbol = symbol;
            log.info("📡 Binance subscribed: {}", symbol);
        } catch (Exception ex) {
            log.error("❌ Ошибка subscribe Binance {}", ex.getMessage());
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
            try {
                bybit.unsubscribeTicker(currentBybitSymbol);
                log.info("🔌 Bybit unsubscribed: {}", currentBybitSymbol);
            } catch (Exception ignored) {}
        }

        try {
            bybit.subscribeTicker(symbol);
            currentBybitSymbol = symbol;
            log.info("📡 Bybit subscribed: {}", symbol);
        } catch (Exception ex) {
            log.error("❌ Bybit subscribe error {}", ex.getMessage());
        }
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
