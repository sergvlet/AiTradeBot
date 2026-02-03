package com.chicu.aitradebot.market;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class TickDispatchTracer {

    @Value("${market.dispatch.traceEnabled:true}")
    private boolean enabled;

    @Value("${market.dispatch.traceEverySkips:300}")
    private int traceEverySkips;

    private final Map<String, Stat> stats = new ConcurrentHashMap<>();

    private static class Stat {
        long total;
        long passed;
        long skipped;
        long lastLogSkipAt;
        String lastSkipReason;
        Instant lastTs;
    }

    public void onPass(String key, Instant ts) {
        if (!enabled) return;
        Stat s = stats.computeIfAbsent(key, k -> new Stat());
        s.total++;
        s.passed++;
        s.lastTs = ts;
    }

    public void onSkip(String key, String reason, Instant ts) {
        if (!enabled) return;
        Stat s = stats.computeIfAbsent(key, k -> new Stat());
        s.total++;
        s.skipped++;
        s.lastSkipReason = reason;
        s.lastTs = ts;

        int every = Math.max(1, traceEverySkips);
        if (s.skipped - s.lastLogSkipAt >= every) {
            s.lastLogSkipAt = s.skipped;
            log.warn("[DISPATCH] SKIP key={} reason={} total={} passed={} skipped={} lastTs={}",
                    key, reason, s.total, s.passed, s.skipped, ts);
        }
    }
}
