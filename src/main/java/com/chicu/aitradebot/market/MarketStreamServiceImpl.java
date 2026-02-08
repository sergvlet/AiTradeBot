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

    @Value("${market.dispatch.logFirstSeq:5}")
    private long logFirstSeq;

    @Value("${market.dispatch.logEverySeq:200}")
    private long logEverySeq;

    @Value("${market.dispatch.inactiveTtlMs:60000}")
    private long inactiveTtlMs;

    @Value("${market.dispatch.skip-log-cooldown-ms:30000}")
    private long skipLogCooldownMs;

    // ============================================================
    // ✅ AGG_TRADE maps (БЕЗ timeframe!)
    // ============================================================

    /**
     * ✅ last успешный DISPATCH в оркестратор (eventTs) по TickKey (без tf)
     */
    private final ConcurrentMap<TickKey, Long> lastTickDispatchAtMs = new ConcurrentHashMap<>();

    /**
     * ✅ last "seen" event по TickKey (чтобы TTL работал даже если не диспатчим)
     */
    private final ConcurrentMap<TickKey, Long> lastTickSeenAtMs = new ConcurrentHashMap<>();

    /**
     * ✅ Анти-спам SKIP логов по TickKey
     */
    private final ConcurrentMap<TickKey, Long> lastTickSkipLogAtMs = new ConcurrentHashMap<>();

    // ============================================================
    // ✅ KLINE maps (с timeframe!)
    // ============================================================

    private final ConcurrentMap<KlineKey, Long> lastKlineSeenAtMs = new ConcurrentHashMap<>();

    /**
     * Диагностический счётчик, если push.seq() == 0
     */
    private final AtomicLong diagCounter = new AtomicLong(0);

    /**
     * ✅ Запоминаем последнюю "подписку" по (chatId,type),
     * чтобы при смене контекста сделать resubscribe.
     */
    private final ConcurrentMap<SubKey, SubBinding> lastSub = new ConcurrentHashMap<>();

    // ============================================================
    // SUBSCRIBE (с ресабом при смене binding)
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

        SubKey k = new SubKey(chatId, type);
        SubBinding desired = new SubBinding(ex, networkType, sym, tf);

        SubBinding prev = lastSub.put(k, desired);

        if (prev != null && !prev.equals(desired)) {
            log.info("🔁 [MARKET] resubscribe chatId={} type={} old=[{} {} {} {}] new=[{} {} {} {}]",
                    chatId, type,
                    prev.exchange, prev.networkType, prev.symbol, prev.timeframe,
                    ex, networkType, sym, tf
            );

            // ✅ безопасная попытка unsubscribe (если метод есть)
            safeUnsubscribe(prev.exchange, prev.networkType, chatId, type, prev.symbol, prev.timeframe);
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

    /**
     * Пытаемся вызвать marketDataStreamService.unsubscribe(...) если метод существует.
     * Ничего не ломаем, если его нет.
     */
    private void safeUnsubscribe(String ex, NetworkType net, long chatId, StrategyType type, String sym, String tf) {
        try {
            Method m = marketDataStreamService.getClass().getMethod(
                    "unsubscribe",
                    String.class, NetworkType.class, long.class, StrategyType.class, String.class, String.class
            );
            m.invoke(marketDataStreamService, ex, net, chatId, type, sym, tf);
            log.info("📴 [MARKET] Unsubscribe OK chatId={} type={} ex={} net={} {} {}",
                    chatId, type, ex, net, sym, tf);
        } catch (NoSuchMethodException ignored) {
            // метода нет — ок
        } catch (Exception e) {
            log.warn("⚠️ [MARKET] Unsubscribe failed chatId={} type={} ex={} net={} {} {} err={}",
                    chatId, type, ex, net, sym, tf, e.getMessage());
        }
    }

    // ============================================================
    // ✅ AGG TRADE (TICK) — НОВАЯ СИГНАТУРА БЕЗ timeframe
    // ============================================================

    @Override
    public void onAggTrade(long chatId,
                           StrategyType type,
                           String exchange,
                           NetworkType networkType,
                           String symbol,
                           BigDecimal price,
                           BigDecimal qty,
                           long tradeTsMs) {

        final String ex  = sanitizeExchange(exchange);
        final String sym = sanitizeSymbol(symbol);

        if (type == null || ex == null || networkType == null || sym == null ||
            price == null || price.signum() <= 0 || tradeTsMs <= 0) {
            return;
        }

        final TickKey key = new TickKey(chatId, type, ex, networkType, sym);
        final long eventMs = tradeTsMs;

        // ✅ lastSeen всегда (TTL работает даже без диспатча)
        lastTickSeenAtMs.put(key, eventMs);

        final Long prevDispatchMs = lastTickDispatchAtMs.get(key);
        final long sinceLastDispatchMs = (prevDispatchMs == null) ? -1L : (eventMs - prevDispatchMs);

        // 0) определяем timeframe для UI/оркестратора (из binding, иначе из lastSub)
        final String tf = resolveTimeframe(chatId, type, ex, networkType, sym);

        // 1) push в stream cache (оставляем старую сигнатуру сервиса, чтобы проект не ломать)
        final MarketDataStreamService.MarketPushResult push;
        try {
            push = marketDataStreamService.pushAggTrade(
                    ex, networkType, chatId, type,
                    sym,
                    (tf != null ? tf : "na"),
                    price, qty, tradeTsMs
            );
        } catch (Exception e) {
            log.error("❌ [MARKET] pushAggTrade упал chatId={} type={} ex={} net={} sym={} err={}",
                    chatId, type, ex, networkType, sym, e.getMessage(), e);
            return;
        }

        // 2) ✅ UI живёт ВСЕГДА (даже если стратегия OFF)
        try {
            livePublisher.publishAggTick(chatId, type, sym, (tf != null ? tf : "na"), price, qty, tradeTsMs);
        } catch (Exception ignored) {}

        if (push != null && push.candleClosed() != null) {
            try {
                livePublisher.publishCandle(chatId, type, push.candleClosed());
            } catch (Exception ignored) {}
        }

        // 3) ✅ торговая логика: диспатчим только если есть binding и он совпал
        Optional<AiStrategyOrchestrator.RunBinding> bindingOpt = orchestrator.getBinding(chatId, type);
        if (bindingOpt.isEmpty()) {
            cleanupTickKeyIfOld(key, eventMs);
            maybeLogTickSkip(key, eventMs, safeSeq(push),
                    chatId, type, ex, networkType, sym, tf, price, qty, tradeTsMs,
                    sinceLastDispatchMs, "Пропуск: стратегия не запущена (binding отсутствует)", push);
            return;
        }

        AiStrategyOrchestrator.RunBinding b = bindingOpt.get();

        // tf обязательно должен совпадать с binding, иначе оркестратор сам будет игнорить и спамить
        boolean bindingMatch =
                eq(ex, b.exchange()) &&
                networkType == b.network() &&
                eq(sym, b.symbol()) &&
                eq(tf, b.timeframe());

        if (!bindingMatch) {
            cleanupTickKeyIfOld(key, eventMs);
            maybeLogTickSkip(key, eventMs, safeSeq(push),
                    chatId, type, ex, networkType, sym, tf, price, qty, tradeTsMs,
                    sinceLastDispatchMs,
                    "Пропуск: контекст не совпал с binding (ожидаю ex=" + b.exchange() + " net=" + b.network()
                    + " " + b.symbol() + " " + b.timeframe() + ")",
                    push);
            return;
        }

        // 4) throttle + dispatch
        boolean dispatched = false;
        String reason;

        final long minInterval = Math.max(0L, minDispatchIntervalMs);
        final boolean allowedByThrottle = (sinceLastDispatchMs < 0) || (sinceLastDispatchMs >= minInterval);

        if (allowedByThrottle) {
            try {
                orchestrator.onPriceUpdate(chatId, type, ex, networkType, sym, tf, price, tradeTsMs);

                // ✅ фиксируем диспатч ТОЛЬКО после успеха
                lastTickDispatchAtMs.put(key, eventMs);

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

        long seq = safeSeq(push);

        if (dispatched) {
            logAggInfo(seq, chatId, type, ex, networkType, sym, tf, price, qty, tradeTsMs,
                    sinceLastDispatchMs, push);
            return;
        }

        maybeLogTickSkip(key, eventMs, seq,
                chatId, type, ex, networkType, sym, tf, price, qty, tradeTsMs,
                sinceLastDispatchMs, reason, push);
    }

    private String resolveTimeframe(long chatId, StrategyType type, String ex, NetworkType net, String sym) {
        try {
            Optional<AiStrategyOrchestrator.RunBinding> b = orchestrator.getBinding(chatId, type);
            if (b.isPresent()) {
                AiStrategyOrchestrator.RunBinding rb = b.get();
                if (eq(ex, rb.exchange()) && net == rb.network() && eq(sym, rb.symbol())) {
                    String tf = sanitizeTf(rb.timeframe());
                    if (tf != null) return tf;
                }
            }
        } catch (Exception ignored) {}

        // fallback: lastSub (последняя подписка из UI/настроек)
        SubBinding sb = lastSub.get(new SubKey(chatId, type));
        if (sb != null && eq(ex, sb.exchange) && net == sb.networkType && eq(sym, sb.symbol)) {
            String tf = sanitizeTf(sb.timeframe);
            if (tf != null) return tf;
        }

        return null;
    }

    private void maybeLogTickSkip(TickKey key,
                                  long eventMs,
                                  long seq,
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

        final long lastSkip = lastTickSkipLogAtMs.getOrDefault(key, 0L);
        final long dtSkip = eventMs - lastSkip;

        if (dtSkip >= Math.max(1000L, skipLogCooldownMs)) {
            lastTickSkipLogAtMs.put(key, eventMs);
            logSkipInfo(seq, chatId, type, ex, net, sym, (tf != null ? tf : "na"),
                    price, qty, ts, sinceLastDispatchMs, reason, push);
        }
    }

    private void cleanupTickKeyIfOld(TickKey key, long nowMs) {
        Long lastSeen = lastTickSeenAtMs.get(key);
        if (lastSeen == null) return;

        long ttl = Math.max(1L, inactiveTtlMs);
        if (nowMs - lastSeen >= ttl) {
            lastTickSeenAtMs.remove(key);
            lastTickDispatchAtMs.remove(key);
            lastTickSkipLogAtMs.remove(key);
        }
    }

    // ============================================================
    // KLINE (CANDLE) — как было, но TTL по lastKlineSeenAtMs
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

        final KlineKey key = new KlineKey(chatId, type, ex, networkType, sym, tf);
        lastKlineSeenAtMs.put(key, System.currentTimeMillis());

        // страховка
        try {
            if (kline.getSymbol() == null || kline.getSymbol().isBlank()) kline.setSymbol(sym);
            if (kline.getTimeframe() == null || kline.getTimeframe().isBlank()) kline.setTimeframe(tf);
        } catch (Exception ignored) {}

        // 1) кэш
        try {
            marketDataStreamService.pushKline(ex, networkType, chatId, type, sym, tf, kline);
        } catch (Exception e) {
            log.error("❌ [MARKET] pushKline(strict) упал chatId={} type={} ex={} net={} sym={} tf={} err={}",
                    chatId, type, ex, networkType, sym, tf, e.getMessage(), e);
            return;
        }

        // 2) UI свеча всегда
        try {
            livePublisher.publishCandle(chatId, type, kline);
        } catch (Exception ignored) {}

        // 3) закрытие свечи -> оркестратор (только если binding совпал)
        if (!isKlineClosed(kline)) return;

        Optional<AiStrategyOrchestrator.RunBinding> bindingOpt = orchestrator.getBinding(chatId, type);
        if (bindingOpt.isEmpty()) {
            cleanupKlineKeyIfOld(key, System.currentTimeMillis());
            return;
        }

        AiStrategyOrchestrator.RunBinding b = bindingOpt.get();

        boolean match =
                eq(ex, b.exchange()) &&
                networkType == b.network() &&
                eq(sym, b.symbol()) &&
                eq(tf, b.timeframe());

        if (!match) {
            cleanupKlineKeyIfOld(key, System.currentTimeMillis());
            return;
        }

        try {
            orchestrator.onCandleClosed(chatId, type, ex, networkType, sym, tf, kline);
        } catch (Exception e) {
            log.error("❌ [MARKET] onCandleClosed упал chatId={} type={} ex={} net={} sym={} tf={} err={}",
                    chatId, type, ex, networkType, sym, tf, e.getMessage(), e);
        }
    }

    private void cleanupKlineKeyIfOld(KlineKey key, long nowMs) {
        Long lastSeen = lastKlineSeenAtMs.get(key);
        if (lastSeen == null) return;

        long ttl = Math.max(1L, inactiveTtlMs);
        if (nowMs - lastSeen >= ttl) {
            lastKlineSeenAtMs.remove(key);
        }
    }

    // ============================================================
    // LOG + HELPERS (как было)
    // ============================================================

    private long safeSeq(MarketDataStreamService.MarketPushResult push) {
        long s = 0L;
        try { s = push != null ? push.seq() : 0L; } catch (Exception ignored) {}
        return (s > 0) ? s : diagCounter.incrementAndGet();
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

        if (!shouldLog(seq)) return;

        String priceStr = price.stripTrailingZeros().toPlainString();
        String qtyStr   = qty != null ? qty.stripTrailingZeros().toPlainString() : "null";

        log.info("📈 [MARKET] AGG_TICK[{}] chatId={} type={} ex={} net={} {} {} price={} qty={} ts={} sinceLastDispatchMs={} cache: tick={} candle={} createdCandle={}",
                seq,
                chatId, type, ex, net, sym, tf,
                priceStr, qtyStr, ts,
                sinceLastDispatchMs,
                safeBool(() -> push != null && push.pushedTick()),
                safeBool(() -> push != null && push.pushedCandle()),
                safeBool(() -> push != null && push.createdCandle())
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

        if (!shouldLog(seq)) return;

        String priceStr = price.stripTrailingZeros().toPlainString();
        String qtyStr   = qty != null ? qty.stripTrailingZeros().toPlainString() : "null";

        log.info("⏭️ [MARKET] SKIP[{}] chatId={} type={} ex={} net={} {} {} price={} qty={} ts={} | {} | sinceLastDispatchMs={} cache: tick={} candle={} createdCandle={} (cooldown={}ms)",
                seq,
                chatId, type, ex, net, sym, tf,
                priceStr, qtyStr, ts,
                reason,
                sinceLastDispatchMs,
                safeBool(() -> push != null && push.pushedTick()),
                safeBool(() -> push != null && push.pushedCandle()),
                safeBool(() -> push != null && push.createdCandle()),
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
        try {
            Optional<Boolean> a = tryBool(kline, "isClosed");
            if (a.isPresent()) return a.get();
            Optional<Boolean> b = tryBool(kline, "getClosed");
            if (b.isPresent()) return b.get();
        } catch (Exception ignored) {}

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

    private static boolean eq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
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

    // ============================================================
    // KEYS
    // ============================================================

    private record TickKey(long chatId,
                           StrategyType type,
                           String exchange,
                           NetworkType networkType,
                           String symbol) {}

    private record KlineKey(long chatId,
                            StrategyType type,
                            String exchange,
                            NetworkType networkType,
                            String symbol,
                            String timeframe) {}

    private record SubKey(long chatId, StrategyType type) {}

    private static final class SubBinding {
        final String exchange;
        final NetworkType networkType;
        final String symbol;
        final String timeframe;

        private SubBinding(String exchange, NetworkType networkType, String symbol, String timeframe) {
            this.exchange = exchange;
            this.networkType = networkType;
            this.symbol = symbol;
            this.timeframe = timeframe;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SubBinding b)) return false;
            return eq(exchange, b.exchange)
                   && networkType == b.networkType
                   && eq(symbol, b.symbol)
                   && eq(timeframe, b.timeframe);
        }

        @Override
        public int hashCode() {
            int r = 17;
            r = 31 * r + (exchange == null ? 0 : exchange.toUpperCase(Locale.ROOT).hashCode());
            r = 31 * r + (networkType == null ? 0 : networkType.hashCode());
            r = 31 * r + (symbol == null ? 0 : symbol.toUpperCase(Locale.ROOT).hashCode());
            r = 31 * r + (timeframe == null ? 0 : timeframe.toLowerCase(Locale.ROOT).hashCode());
            return r;
        }
    }
}
