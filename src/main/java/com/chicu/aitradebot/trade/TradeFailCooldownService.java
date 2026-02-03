package com.chicu.aitradebot.trade;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class TradeFailCooldownService {

    private static final class FailState {
        final String code;
        final long blockedUntilMs;
        final long firstFailMs;
        final int  count;

        FailState(String code, long blockedUntilMs, long firstFailMs, int count) {
            this.code = code;
            this.blockedUntilMs = blockedUntilMs;
            this.firstFailMs = firstFailMs;
            this.count = count;
        }
    }

    /** key -> fail state */
    private final Map<String, FailState> fails = new ConcurrentHashMap<>();

    public boolean isBlocked(String key) {
        FailState s = fails.get(key);
        if (s == null) return false;

        long now = System.currentTimeMillis();
        if (now < s.blockedUntilMs) return true;

        // cooldown закончился — чистим
        fails.remove(key);
        return false;
    }

    public long remainingMs(String key) {
        FailState s = fails.get(key);
        if (s == null) return 0L;
        long now = System.currentTimeMillis();
        return Math.max(0L, s.blockedUntilMs - now);
    }

    public void clear(String key) {
        fails.remove(key);
    }

    public void recordFailure(String key, String code, String reason) {
        long now = System.currentTimeMillis();

        FailState prev = fails.get(key);
        int nextCount = (prev == null) ? 1 : (prev.count + 1);
        long firstFail = (prev == null) ? now : prev.firstFailMs;

        Duration cd = cooldownFor(code, nextCount);
        long until = now + cd.toMillis();

        fails.put(key, new FailState(code, until, firstFail, nextCount));

        // лог один раз на ошибку (не на каждый тик)
        log.warn("⏳ ENTRY COOLDOWN key={} code={} cd={}s count={} reason={}",
                key, code, cd.toSeconds(), nextCount, safe(reason));
    }

    /**
     * Подбираем cooldown по коду.
     * И чуть растим cooldown, если ошибка повторяется подряд.
     */
    private Duration cooldownFor(String code, int count) {
        String c = norm(code);

        Duration base = switch (c) {
            case "lot_step"      -> Duration.ofSeconds(20);
            case "min_notional"  -> Duration.ofSeconds(30);
            case "balance"       -> Duration.ofSeconds(60);
            case "rate_limit"    -> Duration.ofSeconds(5);
            case "exchange_reject" -> Duration.ofSeconds(15);
            default              -> Duration.ofSeconds(10);
        };

        // мягкая эскалация: 1x, 1.5x, 2x, 3x...
        if (count <= 1) return base;
        if (count == 2) return base.plusSeconds(base.toSeconds() / 2);
        if (count == 3) return base.plusSeconds(base.toSeconds());
        return base.plusSeconds(base.toSeconds() * 2L);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(String s) {
        if (s == null) return "";
        String x = s.replace('\n', ' ').replace('\r', ' ').trim();
        return x.length() > 220 ? x.substring(0, 220) + "…" : x;
    }
}
