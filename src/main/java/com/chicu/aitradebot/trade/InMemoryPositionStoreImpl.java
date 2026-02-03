package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale; // ✅ FIX: нужен для Locale.ROOT

/**
 * Простая in-memory реализация для V4:
 * - хранит факт "в позиции"
 * - хранит snapshot (entryPrice/qty/tp/sl) для правильного выхода
 *
 * ⚠️ Если нужно переживать рестарт приложения — позже заменим на DB-реализацию.
 */
@Slf4j
@Service
public class InMemoryPositionStoreImpl implements PositionStore {

    /**
     * Храним по точному ключу (включая symbol).
     */
    private final Map<String, PositionSnapshot> positions = new ConcurrentHashMap<>();

    @Override
    public boolean isInPosition(Long chatId,
                                StrategyType type,
                                String exchange,
                                NetworkType network,
                                String symbol) {

        if (chatId == null || type == null) return false;

        String ex = normUpper(exchange);
        String net = normUpper(network != null ? network.name() : null);

        String sym = normUpper(symbol);
        if (sym == null) {
            // "любой символ" для данного контекста
            String prefix = prefixKey(chatId, type, ex, net);
            for (String k : positions.keySet()) {
                if (k != null && k.startsWith(prefix)) return true;
            }
            return false;
        }

        return positions.containsKey(key(chatId, type, ex, net, sym));
    }

    @Override
    public void markOpened(Long chatId,
                           StrategyType type,
                           String exchange,
                           NetworkType network,
                           String symbol) {

        if (chatId == null || type == null) return;

        String ex = normUpper(exchange);
        String net = normUpper(network != null ? network.name() : null);
        String sym = normUpper(symbol);

        // Если symbol не задан — мы не знаем куда класть (но и не хотим ломать старый код).
        // Поэтому просто логируем и игнорируем, чтобы не создать "мутный" ключ.
        if (sym == null) {
            log.debug("[POS] markOpened skipped (symbol is null) chatId={} type={} ex={} net={}", chatId, type, ex, net);
            return;
        }

        positions.putIfAbsent(
                key(chatId, type, ex, net, sym),
                new PositionSnapshot(chatId, type, ex, network, sym,
                        null, null, null, null,
                        null, null, Instant.now())
        );
    }

    @Override
    public void markOpened(Long chatId,
                           StrategyType type,
                           String exchange,
                           NetworkType network,
                           String symbol,
                           BigDecimal entryPrice,
                           BigDecimal qty,
                           BigDecimal tp,
                           BigDecimal sl,
                           BigDecimal quoteSpent,
                           Long entryOrderId,
                           Instant openedAt) {

        if (chatId == null || type == null) return;

        String ex = normUpper(exchange);
        String net = normUpper(network != null ? network.name() : null);
        String sym = normUpper(symbol);

        if (sym == null) {
            log.debug("[POS] markOpened(snapshot) skipped (symbol is null) chatId={} type={} ex={} net={}", chatId, type, ex, net);
            return;
        }

        Instant ts = (openedAt != null ? openedAt : Instant.now());

        PositionSnapshot snap = new PositionSnapshot(
                chatId,
                type,
                ex,
                network,
                sym,
                positiveOrNull(entryPrice),
                positiveOrNull(qty),
                positiveOrNull(tp),
                positiveOrNull(sl),
                positiveOrNull(quoteSpent),
                entryOrderId,
                ts
        );

        positions.put(key(chatId, type, ex, net, sym), snap);

        log.debug("[POS] OPEN chatId={} type={} ex={} net={} sym={} entryPrice={} qty={} tp={} sl={} orderId={}",
                chatId, type, ex, net, sym,
                toStr(entryPrice), toStr(qty), toStr(tp), toStr(sl), entryOrderId);
    }

    @Override
    public void markClosed(Long chatId,
                           StrategyType type,
                           String exchange,
                           NetworkType network,
                           String symbol) {

        if (chatId == null || type == null) return;

        String ex = normUpper(exchange);
        String net = normUpper(network != null ? network.name() : null);

        String sym = normUpper(symbol);
        if (sym == null) {
            // закрыть "всё" по контексту
            String prefix = prefixKey(chatId, type, ex, net);
            List<String> toRemove = new ArrayList<>();
            for (String k : positions.keySet()) {
                if (k != null && k.startsWith(prefix)) toRemove.add(k);
            }
            toRemove.forEach(positions::remove);

            log.debug("[POS] CLOSE(all) chatId={} type={} ex={} net={} removed={}", chatId, type, ex, net, toRemove.size());
            return;
        }

        positions.remove(key(chatId, type, ex, net, sym));
        log.debug("[POS] CLOSE chatId={} type={} ex={} net={} sym={}", chatId, type, ex, net, sym);
    }

    @Override
    public Optional<PositionSnapshot> getPosition(Long chatId,
                                                  StrategyType type,
                                                  String exchange,
                                                  NetworkType network,
                                                  String symbol) {

        if (chatId == null || type == null) return Optional.empty();

        String ex = normUpper(exchange);
        String net = normUpper(network != null ? network.name() : null);

        String sym = normUpper(symbol);
        if (sym == null) {
            // вернуть любую позицию по контексту (например для "быстрого статуса")
            String prefix = prefixKey(chatId, type, ex, net);
            for (Map.Entry<String, PositionSnapshot> e : positions.entrySet()) {
                if (e.getKey() != null && e.getKey().startsWith(prefix)) return Optional.ofNullable(e.getValue());
            }
            return Optional.empty();
        }

        return Optional.ofNullable(positions.get(key(chatId, type, ex, net, sym)));
    }

    @Override
    public void clearPosition(Long chatId,
                              StrategyType type,
                              String exchange,
                              NetworkType network,
                              String symbol) {
        markClosed(chatId, type, exchange, network, symbol);
    }

    // =====================================================
    // helpers
    // =====================================================

    private static String key(Long chatId, StrategyType type, String ex, String net, String sym) {
        return prefixKey(chatId, type, ex, net) + ":" + sym;
    }

    private static String prefixKey(Long chatId, StrategyType type, String ex, String net) {
        String t = (type != null ? type.name() : "NA");
        String e = (ex != null ? ex : "NA");
        String n = (net != null ? net : "NA");
        return chatId + ":" + t + ":" + e + ":" + n;
    }

    private static String normUpper(String s) {
        if (s == null) return null;
        String v = s.trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    private static BigDecimal positiveOrNull(BigDecimal v) {
        return (v != null && v.signum() > 0) ? v : null;
    }

    private static String toStr(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
    }
}
