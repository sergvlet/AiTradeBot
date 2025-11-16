package com.chicu.aitradebot.market;

import com.chicu.aitradebot.market.model.TradeTick;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 🧠 Потокобезопасный кэш последних трейдов.
 * Используется для генерации 1s/5s/10s свечей.
 */
@Component
public class TradeCacheService {

    private static final Duration RETAIN = Duration.ofMinutes(30); // храним последние 30 минут
    private final Map<String, ConcurrentLinkedQueue<TradeTick>> map = new ConcurrentHashMap<>();

    public void put(TradeTick t) {
        var q = map.computeIfAbsent(t.symbol(), k -> new ConcurrentLinkedQueue<>());
        q.add(t);
        prune(q);
    }

    public List<TradeTick> getRecent(String symbol, int max) {
        var q = map.getOrDefault(symbol, new ConcurrentLinkedQueue<>());
        List<TradeTick> all = new ArrayList<>(q);
        int from = Math.max(0, all.size() - max);
        return all.subList(from, all.size());
    }

    private void prune(Queue<TradeTick> q) {
        var minTs = Instant.now().minus(RETAIN);
        while (true) {
            var head = q.peek();
            if (head == null) break;
            if (head.ts().isBefore(minTs)) q.poll();
            else break;
        }
    }
}
