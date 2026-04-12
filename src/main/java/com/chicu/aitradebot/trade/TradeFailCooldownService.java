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

    /**
     * Если между фейлами пауза больше этого окна — считаем, что серия ошибок закончилась,
     * и сбрасываем эскалацию count обратно к 1.
     */
    private static final long RESET_SERIES_AFTER_MS = Duration.ofMinutes(2).toMillis();

    /**
     * Верхняя граница cooldown, чтобы не “залипать” навсегда при странных сериях ошибок.
     */
    private static final long MAX_COOLDOWN_MS = Duration.ofMinutes(10).toMillis();

    /**
     * Максимальная эскалация (на всякий случай, чтобы count не рос бесконечно).
     */
    private static final int MAX_COUNT = 20;

    private static final class FailState {
        final String code;            // нормализованный код
        final long blockedUntilMs;    // до какого времени блок
        final long firstFailMs;       // начало серии
        final long lastFailMs;        // последний фейл
        final int count;              // счетчик серии

        FailState(String code, long blockedUntilMs, long firstFailMs, long lastFailMs, int count) {
            this.code = code;
            this.blockedUntilMs = blockedUntilMs;
            this.firstFailMs = firstFailMs;
            this.lastFailMs = lastFailMs;
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
        final long now = System.currentTimeMillis();
        final String c = norm(code);

        final FailState[] prevHolder = new FailState[1];
        final FailState[] nextHolder = new FailState[1];
        final boolean[] didUpdate = new boolean[1];

        fails.compute(key, (k, prev) -> {
            prevHolder[0] = prev;

            // Если уже заблокированы — не продлеваем и не спамим логом.
            if (prev != null && now < prev.blockedUntilMs) {
                didUpdate[0] = false;
                nextHolder[0] = prev;
                return prev;
            }

            boolean resetSeries = false;

            if (prev == null) {
                resetSeries = true;
            } else {
                // если код поменялся — считаем это другой проблемой, начинаем серию заново
                if (!prev.code.equals(c)) {
                    resetSeries = true;
                }
                // если долго не было ошибок — серия завершилась
                if ((now - prev.lastFailMs) > RESET_SERIES_AFTER_MS) {
                    resetSeries = true;
                }
            }

            final int nextCount = resetSeries ? 1 : Math.min(MAX_COUNT, prev.count + 1);
            final long firstFail = resetSeries ? now : prev.firstFailMs;

            Duration cd = cooldownFor(c, nextCount);
            long until = now + Math.min(cd.toMillis(), MAX_COOLDOWN_MS);

            FailState next = new FailState(c, until, firstFail, now, nextCount);
            didUpdate[0] = true;
            nextHolder[0] = next;
            return next;
        });

        // логируем только когда реально обновили/поставили cooldown
        if (didUpdate[0]) {
            Duration cd = Duration.ofMillis(Math.max(0L, nextHolder[0].blockedUntilMs - now));
            log.warn("⏳ ENTRY COOLDOWN key={} code={} cd={}s count={} reason={}",
                    key, c, cd.toSeconds(), nextHolder[0].count, safe(reason));
        }
    }

    /**
     * Подбираем cooldown по коду.
     * И чуть растим cooldown, если ошибка повторяется подряд.
     */
    private Duration cooldownFor(String code, int count) {
        String c = norm(code);

        Duration base = switch (c) {
            case "lot_step"         -> Duration.ofSeconds(20);
            case "min_notional"     -> Duration.ofSeconds(30);
            case "balance"          -> Duration.ofSeconds(60);
            case "rate_limit"       -> Duration.ofSeconds(5);
            case "exchange_reject"  -> Duration.ofSeconds(15);
            default                 -> Duration.ofSeconds(10);
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