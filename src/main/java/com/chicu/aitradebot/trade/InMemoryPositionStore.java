package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store (быстрый флаг).
 * Храним множество "открытых позиций" по ключу.
 */
@Slf4j
@Component
public class InMemoryPositionStore implements PositionStore {

    private final Set<Key> opened = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isInPosition(Long chatId,
                                StrategyType type,
                                String exchange,
                                NetworkType network,
                                String symbol) {

        if (chatId == null || type == null || network == null) return false;

        String ex = normExchange(exchange);
        String sym = normSymbol(symbol);

        // Если символ задан — точная проверка
        if (sym != null) {
            return opened.contains(new Key(chatId, type, ex, network, sym));
        }

        // Если символ не задан — любая позиция по (chatId,type,ex,net)
        for (Key k : opened) {
            if (k.chatId == chatId &&
                k.type == type &&
                eq(k.exchange, ex) &&
                k.network == network) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void markOpened(Long chatId,
                           StrategyType type,
                           String exchange,
                           NetworkType network,
                           String symbol) {

        if (chatId == null || type == null || network == null) return;

        String ex = normExchange(exchange);
        String sym = normSymbol(symbol);

        Key key = new Key(chatId, type, ex, network, sym);
        opened.add(key);

        if (log.isDebugEnabled()) {
            log.debug("🧷 Position OPEN  chatId={} type={} ex={} net={} sym={}",
                    chatId, type, ex, network, sym);
        }
    }

    @Override
    public void markClosed(Long chatId,
                           StrategyType type,
                           String exchange,
                           NetworkType network,
                           String symbol) {

        if (chatId == null || type == null || network == null) return;

        String ex = normExchange(exchange);
        String sym = normSymbol(symbol);

        // Если symbol задан — закрываем конкретный
        if (sym != null) {
            opened.remove(new Key(chatId, type, ex, network, sym));
            if (log.isDebugEnabled()) {
                log.debug("🧷 Position CLOSE chatId={} type={} ex={} net={} sym={}",
                        chatId, type, ex, network, sym);
            }
            return;
        }

        // Если symbol не задан — закрываем все позиции в этом контексте
        opened.removeIf(k ->
                k.chatId == chatId &&
                k.type == type &&
                eq(k.exchange, ex) &&
                k.network == network
        );

        if (log.isDebugEnabled()) {
            log.debug("🧷 Position CLOSE ALL chatId={} type={} ex={} net={}",
                    chatId, type, ex, network);
        }
    }

    // ----------------------------------------------------------------------

    private record Key(long chatId,
                       StrategyType type,
                       String exchange,
                       NetworkType network,
                       String symbol) { }

    private static boolean eq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    private static String normExchange(String exchange) {
        if (exchange == null) return null;
        String s = exchange.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }
}
