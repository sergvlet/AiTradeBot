package com.chicu.aitradebot.market;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.chicu.aitradebot.market.stream.MarketDataStreamService;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketStreamServiceImpl implements MarketStreamService {

    private final MarketDataStreamService marketDataStreamService;
    private final StrategyLivePublisher livePublisher;
    private final AiStrategyOrchestrator orchestrator;

    /**
     * Минимальный интервал диспатча в оркестратор (ms).
     * 0 = без троттлинга.
     */
    @Value("${market.dispatch.min-interval-ms:0}")
    private long minDispatchIntervalMs;

    /**
     * Диагностика: логировать первые N сообщений по каждому ключу.
     */
    @Value("${market.dispatch.logFirstSeq:5}")
    private long logFirstSeq;

    /**
     * Диагностика: логировать каждое N-ое сообщение по seq (если seq доступен).
     */
    @Value("${market.dispatch.logEverySeq:200}")
    private long logEverySeq;

    /**
     * TTL для очистки ключей, если стратегия не работает/не диспатчит.
     */
    @Value("${market.dispatch.inactiveTtlMs:60000}")
    private long inactiveTtlMs;

    /**
     * Анти-спам: лог SKIP не чаще 1 раза в X ms на StreamKey
     */
    @Value("${market.dispatch.skip-log-cooldown-ms:30000}")
    private long skipLogCooldownMs;

    /**
     * ✅ Единственный троттлер диспатча
     * last успешный DISPATCH в оркестратор по StreamKey (eventTs)
     */
    private final ConcurrentMap<StreamKey, Long> lastDispatchAtMs = new ConcurrentHashMap<>();

    /**
     * ✅ Анти-спам SKIP логов по StreamKey
     */
    private final ConcurrentMap<StreamKey, Long> lastSkipLogAtMs = new ConcurrentHashMap<>();

    /**
     * Диагностический счётчик на случай, если push.seq() == 0
     */
    private final AtomicLong diagCounter = new AtomicLong(0);

    // ============================================================
    // SUBSCRIBE
    // ============================================================

    @Override
    public void ensureSubscribed(long chatId,
                                 StrategyType type,
                                 String symbol,
                                 String timeframe,
                                 String exchange,
                                 NetworkType networkType) {

        String sym = sanitizeSymbol(symbol);
        String tf  = sanitizeTf(timeframe);
        String ex  = sanitizeExchange(exchange);

        if (type == null || sym == null || tf == null || ex == null || networkType == null) {
            log.warn("⚠️ [MARKET] Подписка пропущена: некорректные параметры chatId={} type={} ex={} net={} symbol={} tf={}",
                    chatId, type, exchange, networkType, symbol, timeframe);
            return;
        }

        try {
            marketDataStreamService.subscribe(ex, networkType, chatId, type, sym, tf);
            log.info("📡 [MARKET] Подписка OK chatId={} type={} ex={} net={} {} {}",
                    chatId, type, ex, networkType, sym, tf);
        } catch (Exception e) {
            log.error("❌ [MARKET] Ошибка подписки chatId={} type={} ex={} net={} sym={} tf={} err={}",
                    chatId, type, ex, networkType, sym, tf, e.getMessage(), e);
        }
    }

    // ============================================================
    // AGG TRADE (TICK)
    // ============================================================

    @Override
    public void onAggTrade(long chatId,
                           StrategyType type,
                           String exchange,
                           NetworkType networkType,
                           String symbol,
                           String timeframe,
                           BigDecimal price,
                           BigDecimal qty,
                           long tradeTsMs) {

        final String ex  = sanitizeExchange(exchange);
        final String sym = sanitizeSymbol(symbol);
        final String tf  = sanitizeTf(timeframe);

        if (type == null || ex == null || networkType == null || sym == null || tf == null ||
            price == null || price.signum() <= 0 || tradeTsMs <= 0) {
            return;
        }

        final MarketDataStreamService.MarketPushResult push;
        try {
            push = marketDataStreamService.pushAggTrade(ex, networkType, chatId, type, sym, tf, price, qty, tradeTsMs);
        } catch (Exception e) {
            log.error("❌ [MARKET] pushAggTrade упал chatId={} type={} ex={} net={} sym={} tf={} err={}",
                    chatId, type, ex, networkType, sym, tf, e.getMessage(), e);
            return;
        }

        // ✅ live тик
        try {
            livePublisher.publishAggTick(chatId, type, sym, tf, price, qty, tradeTsMs);
        } catch (Exception ignored) {}

        // ✅ live свеча (если закрылась внутри pushAggTrade)
        if (push.candleClosed() != null) {
            try {
                livePublisher.publishCandle(chatId, type, push.candleClosed());
            } catch (Exception ignored) {}
        }

        final StreamKey key = new StreamKey(chatId, type, ex, networkType, sym, tf);

        // ✅ используем ts события (важно для sinceLastDispatchMs)
        final long eventMs = tradeTsMs;

        final boolean running = orchestrator.isRunning(chatId, type);

        // prev ДО put()
        final Long prevDispatchMs = lastDispatchAtMs.get(key);
        final long sinceLastDispatchMs = (prevDispatchMs == null) ? -1L : (eventMs - prevDispatchMs);

        boolean dispatched = false;
        String reason;

        if (!running) {
            reason = "Пропуск: стратегия НЕ запущена в оркестраторе";
            cleanupKeyIfOld(key, eventMs);
        } else {
            final long minInterval = Math.max(0L, minDispatchIntervalMs);
            final boolean allowedByThrottle = (sinceLastDispatchMs < 0) || (sinceLastDispatchMs >= minInterval);

            if (allowedByThrottle) {
                try {
                    // ✅ сначала диспатчим
                    orchestrator.onPriceUpdate(chatId, type, ex, networkType, sym, tf, price, tradeTsMs);

                    // ✅ фиксируем диспатч ТОЛЬКО после успеха
                    lastDispatchAtMs.put(key, eventMs);

                    dispatched = true;
                    reason = "Диспатч: отправлено в оркестратор (ok)";
                } catch (Exception e) {
                    reason = "Пропуск: ошибка оркестратора (" + e.getClass().getSimpleName() + ")";
                    log.error("❌ [MARKET] DISPATCH ошибка chatId={} type={} ex={} net={} sym={} tf={} err={}",
                            chatId, type, ex, networkType, sym, tf, e.getMessage(), e);
                }
            } else {
                reason = "Пропуск: троттлинг (прошло " + sinceLastDispatchMs + "ms, минимум " + minInterval + "ms)";
            }
        }

        // ===========================
        // ЛОГИ (без спама)
        // ===========================
        final long seq = safeSeq(push);

        if (dispatched) {
            logAggInfo(seq, chatId, type, ex, networkType, sym, tf, price, qty, tradeTsMs,
                    sinceLastDispatchMs, push);
            return;
        }

        // ✅ редкий лог пропуска
        final long lastSkip = lastSkipLogAtMs.getOrDefault(key, 0L);
        final long dtSkip = eventMs - lastSkip;

        if (dtSkip >= Math.max(1000L, skipLogCooldownMs)) {
            lastSkipLogAtMs.put(key, eventMs);

            logSkipInfo(seq, chatId, type, ex, networkType, sym, tf, price, qty, tradeTsMs,
                    sinceLastDispatchMs, reason, push);
        }
    }

    // ============================================================
    // KLINE (CANDLE)
    // ============================================================

    @Override
    public void onKline(long chatId,
                        StrategyType type,
                        String exchange,
                        NetworkType networkType,
                        String symbol,
                        String timeframe,
                        UnifiedKline kline) {

        if (type == null || kline == null) return;

        final String ex  = sanitizeExchange(exchange);
        final String sym = sanitizeSymbol(symbol);
        final String tf  = sanitizeTf(timeframe);

        if (ex == null || networkType == null || sym == null || tf == null) return;

        // ✅ страховка для UI/потребителей
        try {
            if (kline.getSymbol() == null || kline.getSymbol().isBlank()) kline.setSymbol(sym);
            if (kline.getTimeframe() == null || kline.getTimeframe().isBlank()) kline.setTimeframe(tf);
        } catch (Exception ignored) {}

        // 1) кладём свечу в кэш строго по (sym, tf)
        try {
            marketDataStreamService.pushKline(ex, networkType, chatId, type, sym, tf, kline);
        } catch (Exception e) {
            log.error("❌ [MARKET] pushKline(strict) упал chatId={} type={} ex={} net={} sym={} tf={} err={}",
                    chatId, type, ex, networkType, sym, tf, e.getMessage(), e);
            return;
        }

        // 2) публикуем в UI
        try {
            livePublisher.publishCandle(chatId, type, kline);
        } catch (Exception ignored) {}

        // 3) если свеча закрыта — отправляем в оркестратор
        boolean closed = isKlineClosed(kline);

        if (closed && orchestrator.isRunning(chatId, type)) {
            try {
                orchestrator.onCandleClosed(chatId, type, ex, networkType, sym, tf, kline);
            } catch (Exception e) {
                log.error("❌ [MARKET] onCandleClosed упал chatId={} type={} ex={} net={} sym={} tf={} err={}",
                        chatId, type, ex, networkType, sym, tf, e.getMessage(), e);
            }
        }
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private void cleanupKeyIfOld(StreamKey key, long nowMs) {
        Long last = lastDispatchAtMs.get(key);
        if (last == null) return;

        long ttl = Math.max(1L, inactiveTtlMs);
        if (nowMs - last >= ttl) {
            lastDispatchAtMs.remove(key);
            lastSkipLogAtMs.remove(key);
        }
    }

    private long safeSeq(MarketDataStreamService.MarketPushResult push) {
        long seq = 0L;
        try {
            seq = push != null ? push.seq() : 0L;
        } catch (Exception ignored) {}

        if (seq > 0) return seq;

        // fallback если seq не ведётся
        return diagCounter.incrementAndGet();
    }

    private void logAggInfo(long seq,
                            long chatId,
                            StrategyType type,
                            String ex,
                            NetworkType net,
                            String sym,
                            String tf,
                            BigDecimal price,
                            BigDecimal qty,
                            long ts,
                            long sinceLastDispatchMs,
                            MarketDataStreamService.MarketPushResult push) {

        String priceStr = price.stripTrailingZeros().toPlainString();
        String qtyStr   = qty != null ? qty.stripTrailingZeros().toPlainString() : "null";

        boolean shouldLog = shouldLog(seq);
        if (!shouldLog) return;

        log.info("📈 [MARKET] AGG_TICK[{}] chatId={} type={} ex={} net={} {} {} price={} qty={} ts={} sinceLastDispatchMs={} cache: tick={} candle={} createdCandle={}",
                seq,
                chatId, type, ex, net, sym, tf,
                priceStr, qtyStr, ts,
                sinceLastDispatchMs,
                safeBool(() -> push.pushedTick()),
                safeBool(() -> push.pushedCandle()),
                safeBool(() -> push.createdCandle())
        );
    }

    private void logSkipInfo(long seq,
                             long chatId,
                             StrategyType type,
                             String ex,
                             NetworkType net,
                             String sym,
                             String tf,
                             BigDecimal price,
                             BigDecimal qty,
                             long ts,
                             long sinceLastDispatchMs,
                             String reason,
                             MarketDataStreamService.MarketPushResult push) {

        String priceStr = price.stripTrailingZeros().toPlainString();
        String qtyStr   = qty != null ? qty.stripTrailingZeros().toPlainString() : "null";

        boolean shouldLog = shouldLog(seq);
        if (!shouldLog) return;

        log.info("⏭️ [MARKET] SKIP[{}] chatId={} type={} ex={} net={} {} {} price={} qty={} ts={} | {} | sinceLastDispatchMs={} cache: tick={} candle={} createdCandle={} (cooldown={}ms)",
                seq,
                chatId, type, ex, net, sym, tf,
                priceStr, qtyStr, ts,
                reason,
                sinceLastDispatchMs,
                safeBool(() -> push.pushedTick()),
                safeBool(() -> push.pushedCandle()),
                safeBool(() -> push.createdCandle()),
                skipLogCooldownMs
        );
    }

    private boolean shouldLog(long seq) {
        if (seq <= 0) return true;

        long first = Math.max(0L, logFirstSeq);
        long every = Math.max(1L, logEverySeq);

        if (seq <= first) return true;
        return (seq % every) == 0;
    }

    private boolean isKlineClosed(UnifiedKline kline) {
        // 1) нормальные варианты
        try {
            // если у тебя есть isClosed() / getClosed() в модели — будет работать
            Optional<Boolean> a = tryBool(kline, "isClosed");
            if (a.isPresent()) return a.get();
            Optional<Boolean> b = tryBool(kline, "getClosed");
            if (b.isPresent()) return b.get();
        } catch (Exception ignored) {}

        // 2) fallback: иногда поле/метод называется иначе
        try {
            Optional<Boolean> c = tryBool(kline, "closed");
            return c.orElse(false);
        } catch (Exception ignored) {}

        return false;
    }

    private static boolean safeBool(java.util.concurrent.Callable<Boolean> c) {
        try {
            Boolean v = c.call();
            return v != null && v;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sanitizeSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String sanitizeTf(String tf) {
        if (tf == null) return null;
        String s = tf.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String sanitizeExchange(String exchange) {
        if (exchange == null) return null;
        String s = exchange.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private record StreamKey(long chatId,
                             StrategyType type,
                             String exchange,
                             NetworkType networkType,
                             String symbol,
                             String timeframe) {}

    private static Optional<Boolean> tryBool(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            Object v = m.invoke(target);
            if (v == null) return Optional.empty();
            if (v instanceof Boolean b) return Optional.of(b);
            if (v instanceof String s) return Optional.of(Boolean.parseBoolean(s.trim()));
            return Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
