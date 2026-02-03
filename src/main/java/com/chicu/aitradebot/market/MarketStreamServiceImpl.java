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

    @Value("${market.dispatch.min-interval-ms:0}")
    private long minDispatchIntervalMs;

    @Value("${market.dispatch.logEverySeq:200}")
    private long logEverySeq;

    @Value("${market.dispatch.logFirstSeq:5}")
    private long logFirstSeq;

    @Value("${market.dispatch.inactiveTtlMs:60000}")
    private long inactiveTtlMs;

    private final ConcurrentMap<StreamKey, Long> lastDispatchAtMs = new ConcurrentHashMap<>();
    private final AtomicLong diagCounter = new AtomicLong(0);

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

        try {
            livePublisher.publishAggTick(chatId, type, sym, tf, price, qty, tradeTsMs);
        } catch (Exception ignored) {}

        if (push.candleClosed() != null) {
            try {
                livePublisher.publishCandle(chatId, type, push.candleClosed());
            } catch (Exception ignored) {}
        }

        final StreamKey key = new StreamKey(chatId, type, ex, networkType, sym, tf);

        final long nowMs = System.currentTimeMillis();
        final boolean running = orchestrator.isRunning(chatId, type);

        boolean dispatched = false;
        String reason;

        if (!running) {
            reason = "Пропуск: стратегия НЕ запущена в оркестраторе";
            cleanupKeyIfOld(key, nowMs);
        } else {
            long last = lastDispatchAtMs.getOrDefault(key, 0L);
            long dt = nowMs - last;

            long minInterval = Math.max(0, minDispatchIntervalMs);

            if (dt >= minInterval) {
                lastDispatchAtMs.put(key, nowMs);

                try {
                    orchestrator.onPriceUpdate(chatId, type, ex, networkType, sym, tf, price, tradeTsMs);
                    dispatched = true;
                    reason = "Диспатч: отправлено в оркестратор (ok)";
                } catch (Exception e) {
                    reason = "Пропуск: ошибка оркестратора (" + e.getClass().getSimpleName() + ")";
                    log.error("❌ [MARKET] DISPATCH ошибка chatId={} type={} ex={} net={} sym={} tf={} err={}",
                            chatId, type, ex, networkType, sym, tf, e.getMessage(), e);
                }
            } else {
                reason = "Пропуск: троттлинг (прошло " + dt + "ms, минимум " + minInterval + "ms)";
            }
        }

        long seq = push.seq();
        boolean shouldLog = false;

        if (seq > 0) {
            if (seq <= Math.max(0, logFirstSeq)) shouldLog = true;
            else if (seq % Math.max(1, logEverySeq) == 0) shouldLog = true;
        } else {
            long n = diagCounter.incrementAndGet();
            if (n <= Math.max(0, logFirstSeq)) shouldLog = true;
        }

        if (shouldLog) {
            String priceStr = price.stripTrailingZeros().toPlainString();
            String qtyStr   = qty != null ? qty.stripTrailingZeros().toPlainString() : "null";

            Long lastTs = lastDispatchAtMs.get(key);
            long sinceLast = (lastTs == null) ? -1 : (nowMs - lastTs);

            log.info("📈 [MARKET] AGG_TICK[{}] chatId={} type={} ex={} net={} {} {} price={} qty={} ts={} | running={} dispatched={} | {} | sinceLastDispatchMs={} cache: tick={} candle={} createdCandle={}",
                    seq,
                    chatId, type, ex, networkType, sym, tf,
                    priceStr, qtyStr, tradeTsMs,
                    running, dispatched,
                    reason,
                    sinceLast,
                    push.pushedTick(), push.pushedCandle(), push.createdCandle()
            );
        }
    }

    /**
     * ✅ СТРОГИЙ вход: symbol + timeframe приходят явно от WS-клиента.
     * ✅ СТРОГИЙ кэш: кладём в MarketDataStreamService.pushKline(strict).
     */
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

        // ✅ гарантируем заполнение для UI (и на всякий случай для сторонних потребителей)
        try {
            if (kline.getSymbol() == null || kline.getSymbol().isBlank()) kline.setSymbol(sym);
            if (kline.getTimeframe() == null || kline.getTimeframe().isBlank()) kline.setTimeframe(tf);
        } catch (Exception ignored) {}

        // 1) ✅ КЛЮЧЕВОЕ: кладём kline в кэш СТРОГО по (sym,tf)
        try {
            marketDataStreamService.pushKline(ex, networkType, chatId, type, sym, tf, kline);
        } catch (Exception e) {
            log.error("❌ [MARKET] pushKline(strict) упал chatId={} type={} ex={} net={} sym={} tf={} err={}",
                    chatId, type, ex, networkType, sym, tf, e.getMessage(), e);
            return;
        }

        // 2) публикуем свечу в UI
        try {
            livePublisher.publishCandle(chatId, type, kline);
        } catch (Exception ignored) {}

        // 3) если свеча закрыта — уведомляем оркестратор строго с контекстом
        boolean closed = tryBool(kline, "isClosed")
                .or(() -> tryBool(kline, "getClosed"))
                .or(() -> tryBool(kline, "closed"))
                .orElse(false);

        if (closed && orchestrator.isRunning(chatId, type)) {
            try {
                orchestrator.onCandleClosed(chatId, type, ex, networkType, sym, tf, kline);
            } catch (Exception e) {
                log.error("❌ [MARKET] onCandleClosed упал chatId={} type={} ex={} net={} sym={} tf={} err={}",
                        chatId, type, ex, networkType, sym, tf, e.getMessage(), e);
            }
        }
    }

    // ======================================================================
    // helpers
    // ======================================================================

    private void cleanupKeyIfOld(StreamKey key, long nowMs) {
        Long last = lastDispatchAtMs.get(key);
        if (last == null) return;

        long ttl = Math.max(1, inactiveTtlMs);
        if (nowMs - last >= ttl) {
            lastDispatchAtMs.remove(key);
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
