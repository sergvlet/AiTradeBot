package com.chicu.aitradebot.market;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.chicu.aitradebot.market.stream.MarketDataStreamService;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
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

    /** ленивый доступ, чтобы разорвать цикл MarketStreamServiceImpl <-> AiStrategyOrchestrator */
    private final ObjectProvider<AiStrategyOrchestrator> orchestratorProvider;

    private AiStrategyOrchestrator orch() {
        return orchestratorProvider != null ? orchestratorProvider.getIfAvailable() : null;
    }

    private Optional<AiStrategyOrchestrator.RunBinding> bindingOf(long chatId, StrategyType type) {
        AiStrategyOrchestrator o = orch();
        return o != null ? o.getBinding(chatId, type) : Optional.empty();
    }

    @Value("${market.dispatch.min-interval-ms:0}")
    private long minDispatchIntervalMs;

    /**
     * UI: как часто отправлять forming-candle по WS (ms).
     */
    @Value("${market.ui.candle-interval-ms:300}")
    private long uiCandleIntervalMs;

    @Value("${market.dispatch.logFirstSeq:5}")
    private long logFirstSeq;

    @Value("${market.dispatch.logEverySeq:200}")
    private long logEverySeq;

    @Value("${market.dispatch.inactiveTtlMs:60000}")
    private long inactiveTtlMs;

    @Value("${market.dispatch.skip-log-cooldown-ms:30000}")
    private long skipLogCooldownMs;

    // ============================================================
    // NEW: KLINE -> PRICE DISPATCH
    // ============================================================

    /** Включить диспатч onPriceUpdate из kline-апдейтов (forming candle). */
    @Value("${market.dispatch.kline-price.enabled:true}")
    private boolean klinePriceDispatchEnabled;

    /** Минимальный интервал диспатча цены из kline (ms). */
    @Value("${market.dispatch.kline-price.min-interval-ms:250}")
    private long klinePriceMinIntervalMs;

    // ============================================================
    // NEW: DEGRADED GUARD
    // ============================================================

    /** Не пускать торговую логику при деградации каналов. UI и кэш продолжают работать. */
    @Value("${market.dispatch.skip-when-degraded:true}")
    private boolean skipWhenDegraded;

    // ============================================================
    // AGG_TRADE maps (БЕЗ timeframe)
    // ============================================================

    /** last успешный DISPATCH в оркестратор (по wall-clock, ms) */
    private final ConcurrentMap<TickKey, Long> lastTickDispatchAtMs = new ConcurrentHashMap<>();

    /** last seen (по wall-clock, ms) */
    private final ConcurrentMap<TickKey, Long> lastTickSeenAtMs = new ConcurrentHashMap<>();

    /** anti-spam SKIP log (по wall-clock, ms) */
    private final ConcurrentMap<TickKey, Long> lastTickSkipLogAtMs = new ConcurrentHashMap<>();

    // ============================================================
    // KLINE maps (с timeframe)
    // ============================================================

    private final ConcurrentMap<KlineKey, Long> lastKlineSeenAtMs = new ConcurrentHashMap<>();

    /** last успешный DISPATCH цены из KLINE (по wall-clock, ms) */
    private final ConcurrentMap<KlineKey, Long> lastKlinePriceDispatchAtMs = new ConcurrentHashMap<>();

    /** последняя отправка forming-candle по WS */
    private final ConcurrentMap<KlineKey, Long> lastUiCandleAtMs = new ConcurrentHashMap<>();

    /** anti-spam для skip по KLINE */
    private final ConcurrentMap<KlineKey, Long> lastKlineSkipLogAtMs = new ConcurrentHashMap<>();

    /** Диагностический счётчик, если push.seq() == 0 */
    private final AtomicLong diagCounter = new AtomicLong(0);

    /** Последняя подписка (chatId,type) -> binding */
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
        String tf = sanitizeTf(timeframe);
        String ex = sanitizeExchange(exchange);

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

            try {
                marketDataStreamService.unsubscribe(prev.exchange, prev.networkType, chatId, type, prev.symbol, prev.timeframe);            } catch (Exception e) {
                log.warn("⚠️ [MARKET] Unsubscribe failed chatId={} type={} ex={} net={} {} {} err={}",
                        chatId, type, prev.exchange, prev.networkType, prev.symbol, prev.timeframe, e.getMessage());
            }
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
    public void unsubscribe(long chatId, StrategyType type) {
        if (chatId <= 0 || type == null) return;

        SubKey k = new SubKey(chatId, type);
        SubBinding prev = lastSub.remove(k);
        if (prev == null) return;

        try {
            marketDataStreamService.unsubscribe(prev.exchange, prev.networkType, chatId, type, prev.symbol, prev.timeframe);
            log.info("📴 [MARKET] Unsubscribe OK chatId={} type={} ex={} net={} {} {}",
                    chatId, type, prev.exchange, prev.networkType, prev.symbol, prev.timeframe);
        } catch (Exception e) {
            log.warn("⚠️ [MARKET] Unsubscribe failed chatId={} type={} ex={} net={} {} {} err={}",
                    chatId, type, prev.exchange, prev.networkType, prev.symbol, prev.timeframe, e.toString());
        }
    }

    // ============================================================
    // AGG TRADE (TICK) — НОВАЯ СИГНАТУРА БЕЗ timeframe
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

        final String ex = sanitizeExchange(exchange);
        final String sym = sanitizeSymbol(symbol);

        if (type == null || ex == null || networkType == null || sym == null ||
            price == null || price.signum() <= 0 || tradeTsMs <= 0) {
            return;
        }

        final long nowMs = System.currentTimeMillis();
        final TickKey key = new TickKey(chatId, type, ex, networkType, sym);

        // lastSeen всегда
        lastTickSeenAtMs.put(key, nowMs);

        final Long prevDispatchMs = lastTickDispatchAtMs.get(key);
        final long sinceLastDispatchMs = (prevDispatchMs == null) ? -1L : (nowMs - prevDispatchMs);

        // определяем timeframe для UI/оркестратора
        final String tf = resolveTimeframe(chatId, type, ex, networkType, sym);

        // 1) push в stream cache
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

        // если из тиков построили/обновили свечу — публикуем forming-candle
        if (push != null && push.pushedCandle()) {
            tryPublishUiCandleFromCache(chatId, type, ex, networkType, sym, tf, tradeTsMs);
        }

        // 2) UI живёт всегда
        try {
            livePublisher.publishAggTick(chatId, type, sym, (tf != null ? tf : "na"), price, qty, tradeTsMs);
        } catch (Exception ignored) {
        }

        if (push != null && push.candleClosed() != null) {
            try {
                livePublisher.publishCandle(chatId, type, push.candleClosed());
            } catch (Exception ignored) {
            }
        }

        // 3) торговая логика: только если есть binding и он совпал
        Optional<AiStrategyOrchestrator.RunBinding> bindingOpt = bindingOf(chatId, type);
        if (bindingOpt.isEmpty()) {
            cleanupTickKeyIfOld(key, nowMs);
            maybeLogTickSkip(key, nowMs, safeSeq(push),
                    chatId, type, ex, networkType, sym, tf, price, qty, tradeTsMs,
                    sinceLastDispatchMs,
                    "Пропуск: стратегия не запущена (binding отсутствует)",
                    push);
            return;
        }

        AiStrategyOrchestrator.RunBinding b = bindingOpt.get();

        boolean bindingMatch =
                eq(ex, b.exchange()) &&
                networkType == b.network() &&
                eq(sym, b.symbol()) &&
                eq(tf, b.timeframe());

        if (!bindingMatch) {
            cleanupTickKeyIfOld(key, nowMs);
            maybeLogTickSkip(key, nowMs, safeSeq(push),
                    chatId, type, ex, networkType, sym, tf, price, qty, tradeTsMs,
                    sinceLastDispatchMs,
                    "Пропуск: контекст не совпал с binding (ожидаю ex=" + b.exchange() + " net=" + b.network()
                    + " " + b.symbol() + " " + b.timeframe() + ")",
                    push);
            return;
        }

        // 3.1) degraded guard
        if (skipWhenDegraded) {
            MarketDataStreamService.SubscriptionHealth health = resolveHealth(chatId, type, ex, networkType, sym, tf);
            if (health != null && health.degraded()) {
                maybeLogTickSkip(key, nowMs, safeSeq(push),
                        chatId, type, ex, networkType, sym, tf, price, qty, tradeTsMs,
                        sinceLastDispatchMs,
                        "Пропуск: market degraded (" + formatHealthReason(health) + ")",
                        push);
                return;
            }
        }

        // 4) throttle + dispatch
        boolean dispatched = false;
        String reason;

        final long minInterval = Math.max(0L, minDispatchIntervalMs);
        final boolean allowedByThrottle = (sinceLastDispatchMs < 0) || (sinceLastDispatchMs >= minInterval);

        if (allowedByThrottle) {
            try {
                AiStrategyOrchestrator o = orch();
                if (o == null) {
                    reason = "Пропуск: orchestrator отсутствует";
                } else {
                    o.onPriceUpdate(chatId, type, ex, networkType, sym, tf, price, tradeTsMs);

                    // фиксируем диспатч только после успеха
                    lastTickDispatchAtMs.put(key, nowMs);

                    dispatched = true;
                    reason = "Диспатч: отправлено в оркестратор (ok)";
                }
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

        maybeLogTickSkip(key, nowMs, seq,
                chatId, type, ex, networkType, sym, tf, price, qty, tradeTsMs,
                sinceLastDispatchMs, reason, push);
    }

    // старый вызов, если где-то ещё остался
    public void onAggTrade(long chatId,
                           StrategyType type,
                           String exchange,
                           NetworkType networkType,
                           String symbol,
                           String timeframeIgnored,
                           BigDecimal price,
                           BigDecimal qty,
                           long tradeTsMs) {
        onAggTrade(chatId, type, exchange, networkType, symbol, price, qty, tradeTsMs);
    }

    private String resolveTimeframe(long chatId, StrategyType type, String ex, NetworkType net, String sym) {
        try {
            Optional<AiStrategyOrchestrator.RunBinding> b = bindingOf(chatId, type);
            if (b.isPresent()) {
                AiStrategyOrchestrator.RunBinding rb = b.get();
                if (eq(ex, rb.exchange()) && net == rb.network() && eq(sym, rb.symbol())) {
                    String tf = sanitizeTf(rb.timeframe());
                    if (tf != null) return tf;
                }
            }
        } catch (Exception ignored) {
        }

        SubBinding sb = lastSub.get(new SubKey(chatId, type));
        if (sb != null && eq(ex, sb.exchange) && net == sb.networkType && eq(sym, sb.symbol)) {
            String tf = sanitizeTf(sb.timeframe);
            if (tf != null) return tf;
        }

        return null;
    }

    /**
     * Публикуем текущую (forming) свечу по WS из кэша.
     */
    private void tryPublishUiCandleFromCache(long chatId,
                                             StrategyType type,
                                             String exchange,
                                             NetworkType networkType,
                                             String symbol,
                                             String timeframe,
                                             long tradeTsMs) {

        String ex = sanitizeExchange(exchange);
        String sym = sanitizeSymbol(symbol);
        String tf = sanitizeTf(timeframe);

        if (chatId <= 0 || type == null || ex == null || networkType == null || sym == null || tf == null) return;

        long nowMs = System.currentTimeMillis();
        long interval = Math.max(50L, uiCandleIntervalMs);

        KlineKey key = new KlineKey(chatId, type, ex, networkType, sym, tf);

        Long last = lastUiCandleAtMs.get(key);
        if (last != null && (nowMs - last) < interval) return;
        lastUiCandleAtMs.put(key, nowMs);

        try {
            var tail = marketDataStreamService.getCachedCandles(chatId, type, ex, networkType, sym, tf, 1);
            if (tail == null || tail.isEmpty()) return;

            var c = tail.get(tail.size() - 1);
            if (c == null) return;

            Instant ts = Instant.ofEpochMilli(c.getTime());

            livePublisher.pushCandleOhlc(
                    chatId,
                    type,
                    sym,
                    tf,
                    BigDecimal.valueOf(c.getOpen()),
                    BigDecimal.valueOf(c.getHigh()),
                    BigDecimal.valueOf(c.getLow()),
                    BigDecimal.valueOf(c.getClose()),
                    BigDecimal.valueOf(c.getVolume()),
                    ts
            );
        } catch (Exception ignored) {
        }
    }

    private void maybeLogTickSkip(TickKey key,
                                  long nowMs,
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
        final long dtSkip = nowMs - lastSkip;

        if (dtSkip >= Math.max(1000L, skipLogCooldownMs)) {
            lastTickSkipLogAtMs.put(key, nowMs);
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
    // KLINE (CANDLE)
    // ============================================================

    /**
     * Legacy overload: старый контракт MarketStreamService без timeframe.
     */
    @Override
    public void onKline(long chatId,
                        StrategyType type,
                        String exchange,
                        NetworkType networkType,
                        String symbol,
                        UnifiedKline kline) {

        String ex = sanitizeExchange(exchange);
        String sym = sanitizeSymbol(symbol);

        if (kline != null && sym == null) {
            sym = sanitizeSymbol(kline.getSymbol());
        }

        if (ex == null || networkType == null || type == null || sym == null || kline == null) {
            return;
        }

        String tf = null;
        try {
            tf = sanitizeTf(kline.getTimeframe());
        } catch (Exception ignored) {
        }

        if (tf == null) {
            tf = resolveTimeframe(chatId, type, ex, networkType, sym);
        }

        if (tf == null) {
            if (log.isDebugEnabled()) {
                log.debug("⏭️ [MARKET] SKIP kline without timeframe chatId={} type={} ex={} net={} sym={}",
                        chatId, type, ex, networkType, sym);
            }
            return;
        }

        onKline(chatId, type, ex, networkType, sym, tf, kline);
    }

    @Override
    public void onKline(long chatId,
                        StrategyType type,
                        String exchange,
                        NetworkType networkType,
                        String symbol,
                        String timeframe,
                        UnifiedKline kline) {

        if (type == null || kline == null) return;

        final String ex = sanitizeExchange(exchange);
        final String sym = sanitizeSymbol(symbol);
        final String tf = sanitizeTf(timeframe);

        if (ex == null || networkType == null || sym == null || tf == null) return;

        final long nowMs = System.currentTimeMillis();
        final KlineKey key = new KlineKey(chatId, type, ex, networkType, sym, tf);
        lastKlineSeenAtMs.put(key, nowMs);

        try {
            if (kline.getSymbol() == null || kline.getSymbol().isBlank()) kline.setSymbol(sym);
            if (kline.getTimeframe() == null || kline.getTimeframe().isBlank()) kline.setTimeframe(tf);
        } catch (Exception ignored) {
        }

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
        } catch (Exception ignored) {
        }

        // 3) degraded guard для торговой логики
        MarketDataStreamService.SubscriptionHealth health = null;
        if (skipWhenDegraded) {
            health = resolveHealth(chatId, type, ex, networkType, sym, tf);
        }

        boolean marketDegraded = health != null && health.degraded();

        // 4) kline -> price update (forming candle тоже)
        // Для WINDOW_SCALPING это лишний дубль:
        // цена и так идёт через AGG_TRADE, а свеча отдельно через onCandleClosed().
        // Если не отключить этот путь, стратегия получает лишние onPriceUpdate(),
        // повторно логирует HOLD и чаще дёргает ML.
        if (klinePriceDispatchEnabled && !marketDegraded && type != StrategyType.WINDOW_SCALPING) {
            Optional<AiStrategyOrchestrator.RunBinding> bindingOpt = bindingOf(chatId, type);
            if (bindingOpt.isPresent()) {
                AiStrategyOrchestrator.RunBinding b = bindingOpt.get();

                boolean match =
                        eq(ex, b.exchange()) &&
                        networkType == b.network() &&
                        eq(sym, b.symbol()) &&
                        eq(tf, b.timeframe());

                if (match) {
                    BigDecimal px = extractClosePriceSafe(kline);
                    if (px != null && px.signum() > 0) {
                        long minInt = Math.max(0L, klinePriceMinIntervalMs);
                        Long prev = lastKlinePriceDispatchAtMs.get(key);
                        long since = (prev == null) ? Long.MAX_VALUE : (nowMs - prev);

                        if (since >= minInt) {
                            long tsMs = extractEventTimeMsSafe(kline, nowMs);

                            try {
                                AiStrategyOrchestrator o = orch();
                                if (o != null) {
                                    o.onPriceUpdate(chatId, type, ex, networkType, sym, tf, px, tsMs);
                                    lastKlinePriceDispatchAtMs.put(key, nowMs);
                                }
                            } catch (Exception e) {
                                log.error("❌ [MARKET] KLINE->PRICE DISPATCH ошибка chatId={} type={} ex={} net={} sym={} tf={} err={}",
                                        chatId, type, ex, networkType, sym, tf, e.getMessage(), e);
                            }
                        }
                    }
                }
            }
        } else if (marketDegraded) {
            maybeLogKlineSkip(key, nowMs,
                    "Пропуск KLINE->PRICE: market degraded (" + formatHealthReason(health) + ")");
        }

        // 5) закрытие свечи -> оркестратор
        if (!isKlineClosed(kline)) return;

        Optional<AiStrategyOrchestrator.RunBinding> bindingOpt = bindingOf(chatId, type);
        if (bindingOpt.isEmpty()) {
            cleanupKlineKeyIfOld(key, nowMs);
            return;
        }

        AiStrategyOrchestrator.RunBinding b = bindingOpt.get();

        boolean match =
                eq(ex, b.exchange()) &&
                networkType == b.network() &&
                eq(sym, b.symbol()) &&
                eq(tf, b.timeframe());

        if (!match) {
            cleanupKlineKeyIfOld(key, nowMs);
            return;
        }

        if (marketDegraded) {
            maybeLogKlineSkip(key, nowMs,
                    "Пропуск onCandleClosed: market degraded (" + formatHealthReason(health) + ")");
            return;
        }

        try {
            AiStrategyOrchestrator o = orch();
            if (o != null) {
                o.onCandleClosed(chatId, type, ex, networkType, sym, tf, kline);
            }
        } catch (Exception e) {
            log.error("❌ [MARKET] onCandleClosed упал chatId={} type={} ex={} net={} sym={} tf={} err={}",
                    chatId, type, ex, networkType, sym, tf, e.getMessage(), e);
        }
    }

    // старый вызов без env, если где-то остался
    public void onKline(long chatId,
                        StrategyType type,
                        String symbol,
                        String timeframe,
                        UnifiedKline kline) {
        Optional<AiStrategyOrchestrator.RunBinding> b = bindingOf(chatId, type);
        if (b.isEmpty()) return;
        AiStrategyOrchestrator.RunBinding rb = b.get();
        onKline(chatId, type, rb.exchange(), rb.network(), symbol, timeframe, kline);
    }

    private void cleanupKlineKeyIfOld(KlineKey key, long nowMs) {
        Long lastSeen = lastKlineSeenAtMs.get(key);
        if (lastSeen == null) return;

        long ttl = Math.max(1L, inactiveTtlMs);
        if (nowMs - lastSeen >= ttl) {
            lastKlineSeenAtMs.remove(key);
            lastKlinePriceDispatchAtMs.remove(key);
            lastKlineSkipLogAtMs.remove(key);
        }
    }

    // ============================================================
    // LOG + HELPERS
    // ============================================================

    private long safeSeq(MarketDataStreamService.MarketPushResult push) {
        long s = 0L;
        try {
            s = push != null ? push.seq() : 0L;
        } catch (Exception ignored) {
        }
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
        String qtyStr = qty != null ? qty.stripTrailingZeros().toPlainString() : "null";

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
        String qtyStr = qty != null ? qty.stripTrailingZeros().toPlainString() : "null";

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

    private void maybeLogKlineSkip(KlineKey key, long nowMs, String reason) {
        long lastSkip = lastKlineSkipLogAtMs.getOrDefault(key, 0L);
        long dtSkip = nowMs - lastSkip;

        if (dtSkip >= Math.max(1000L, skipLogCooldownMs)) {
            lastKlineSkipLogAtMs.put(key, nowMs);
            log.info("⏭️ [MARKET] KLINE SKIP chatId={} type={} ex={} net={} {} {} | {} (cooldown={}ms)",
                    key.chatId(), key.type(), key.exchange(), key.networkType(), key.symbol(), key.timeframe(),
                    reason, skipLogCooldownMs);
        }
    }

    private boolean shouldLog(long seq) {
        if (seq <= 0) return true;
        long first = Math.max(0L, logFirstSeq);
        long every = Math.max(1L, logEverySeq);
        if (seq <= first) return true;
        return (seq % every) == 0;
    }

    private boolean isKlineClosed(UnifiedKline kline) {
        return kline != null && kline.isClosed();
    }

    private MarketDataStreamService.SubscriptionHealth resolveHealth(long chatId,
                                                                     StrategyType type,
                                                                     String ex,
                                                                     NetworkType net,
                                                                     String sym,
                                                                     String tf) {
        try {
            return marketDataStreamService.getSubscriptionHealth(chatId, type, ex, net, sym, tf);
        } catch (Exception e) {
            log.debug("Health check failed chatId={} type={} ex={} net={} sym={} tf={} err={}",
                    chatId, type, ex, net, sym, tf, e.toString());
            return null;
        }
    }

    private String formatHealthReason(MarketDataStreamService.SubscriptionHealth health) {
        if (health == null) return "unknown";
        return health.reason()
               + ",kAge=" + health.lastKlineAgeMs()
               + ",aggAge=" + health.lastAggTradeAgeMs()
               + ",bookAge=" + health.lastBookTickerAgeMs();
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

    // ============================================================
    // KLINE extractors
    // ============================================================

    private static BigDecimal extractClosePriceSafe(UnifiedKline kline) {
        return kline != null ? kline.getClose() : null;
    }

    private static long extractEventTimeMsSafe(UnifiedKline kline, long fallbackNowMs) {
        if (kline == null) return fallbackNowMs;

        long t = kline.getCloseTime();
        if (t <= 0) t = kline.getOpenTime();
        return t > 0 ? t : fallbackNowMs;
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
