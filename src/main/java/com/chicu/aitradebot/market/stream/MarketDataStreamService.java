package com.chicu.aitradebot.market.stream;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.binance.ws.BinanceSpotWebSocketClient;
import com.chicu.aitradebot.exchange.bybit.BybitMarketStreamAdapter;
import com.chicu.aitradebot.market.MarketStreamManager;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.market.model.UnifiedKline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class MarketDataStreamService {

    private static final int MAX_CANDLES = 2_000;

    /** цикл разорван — берём лениво */
    private final ObjectProvider<BinanceSpotWebSocketClient> binanceWsProvider;
    private final ObjectProvider<BybitMarketStreamAdapter> bybitWsProvider;

    /** общий кэш свечей для бэктеста/дашборда */
    private final MarketStreamManager streamManager;

    /** публикуем события */
    private final ApplicationEventPublisher eventPublisher;

    /** seq для логов/троттлинга */
    private final AtomicLong seq = new AtomicLong(0);

    /**
     * Хранилище свечей строго по ключу:
     * (chatId, type, ex, net, symbol, tf)
     */
    private final ConcurrentMap<CandleStoreKey, Deque<Candle>> candleStorage = new ConcurrentHashMap<>();

    /**
     * chatId -> set of подписок
     */
    private final ConcurrentMap<Long, Set<SubscriptionKey>> activeSubscriptions = new ConcurrentHashMap<>();

    /**
     * Состояние для троттлинга late-kline логов.
     * Bybit может повторно присылать старые незакрытые свечи — это штатно и не должно засорять warn-лог.
     */
    private final ConcurrentMap<String, LateKlineLogState> lateKlineLogStates = new ConcurrentHashMap<>();

    @Value("${market.stream.health.maxSilenceMs:45000}")
    private long maxSilenceMs;

    @Value("${market.stream.health.requireFastChannel:false}")
    private boolean requireFastChannel;

    @Value("${market.stream.health.fastChannelWarmupMs:12000}")
    private long fastChannelWarmupMs;

    /**
     * BOOK_TICKER часто даёт лишний шум для скальпинга.
     * По умолчанию выключаем его и включаем только осознанно через properties.
     */
    @Value("${market.stream.bookTicker.enabled:false}")
    private boolean bookTickerEnabled;

    /**
     * Отдельный флаг именно для WindowScalping.
     * Даже если bookTicker глобально включён, для WINDOW_SCALPING его можно держать выключенным.
     */
    @Value("${market.stream.bookTicker.windowScalping.enabled:false}")
    private boolean windowScalpingBookTickerEnabled;

    /**
     * Как часто разрешено писать сводный лог по поздним свечам для одного стрима.
     */
    @Value("${market.stream.lateKline.logThrottleMs:15000}")
    private long lateKlineLogThrottleMs;

    /**
     * После скольких тихо проигнорированных late-kline стоит вывести debug/info даже если throttle ещё не истёк.
     */
    @Value("${market.stream.lateKline.sampleEvery:100}")
    private int lateKlineSampleEvery;

    public MarketDataStreamService(ObjectProvider<BinanceSpotWebSocketClient> binanceWsProvider,
                                   ObjectProvider<BybitMarketStreamAdapter> bybitWsProvider,
                                   MarketStreamManager streamManager,
                                   ApplicationEventPublisher eventPublisher) {
        this.binanceWsProvider = binanceWsProvider;
        this.bybitWsProvider = bybitWsProvider;
        this.streamManager = streamManager;
        this.eventPublisher = eventPublisher;
    }

    // =====================================================================
    // EVENTS
    // =====================================================================

    public static record MarketTickEvent(
            String exchange,
            NetworkType networkType,
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            BigDecimal price,
            BigDecimal qty,
            long tsMs
    ) {}

    public static record CandleClosedEvent(
            String exchange,
            NetworkType networkType,
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            UnifiedKline kline
    ) {}

    public static record SubscriptionHealth(
            boolean subscribed,
            boolean klineConnected,
            boolean aggTradeConnected,
            boolean bookTickerConnected,
            long lastKlineAgeMs,
            long lastAggTradeAgeMs,
            long lastBookTickerAgeMs,
            boolean degraded,
            String reason
    ) {}

    // =====================================================================
    // API ДЛЯ MarketStreamServiceImpl
    // =====================================================================

    public void subscribe(String exchange,
                          NetworkType networkType,
                          long chatId,
                          StrategyType strategyType,
                          String symbol,
                          String timeframe) {
        subscribeCandles(exchange, networkType, chatId, strategyType, symbol, timeframe);
    }

    public void unsubscribe(String exchange,
                            NetworkType networkType,
                            long chatId,
                            StrategyType strategyType,
                            String symbol,
                            String timeframe) {

        String ex = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf = normTf(timeframe);

        if (ex == null || networkType == null || strategyType == null || sym == null || tf == null) return;

        Set<SubscriptionKey> subs = activeSubscriptions.get(chatId);
        if (subs == null || subs.isEmpty()) return;

        SubscriptionKey key = new SubscriptionKey(strategyType, ex, networkType, sym, tf);

        boolean removed = subs.remove(key);
        if (!removed) return;

        candleStorage.remove(new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf));
        clearLateKlineLogState(chatId, strategyType, ex, networkType, sym, tf);

        try {
            switch (ex) {
                case "BINANCE" -> unsubscribeBinanceChannels(networkType, sym, tf, chatId, strategyType);
                case "BYBIT" -> unsubscribeBybitChannels(networkType, sym, tf, chatId, strategyType);
                default -> log.debug("⏭ [STREAM] unsubscribe: WS cleanup skipped for unsupported exchange={}", ex);
            }
        } catch (Exception e) {
            log.warn("⚠️ [STREAM] unsubscribe channel cleanup failed chatId={} type={} ex={} net={} {} {} err={}",
                    chatId, strategyType, ex, networkType, sym, tf, e.getMessage());
        }

        if (subs.isEmpty()) {
            activeSubscriptions.remove(chatId, subs);
        }

        log.info("📴 [STREAM] UNSUBSCRIBE: chatId={} type={} ex={} net={} {} {}",
                chatId, strategyType, ex, networkType, sym, tf);
    }

    /**
     * Реальный tick:
     * - строим synthetic candle по timeframe (из тиков)
     * - обновляем candleStorage + streamManager
     * - при смене бакета возвращаем candleClosed (UnifiedKline)
     */
    public MarketPushResult pushAggTrade(String exchange,
                                         NetworkType networkType,
                                         long chatId,
                                         StrategyType strategyType,
                                         String symbol,
                                         String timeframe,
                                         BigDecimal price,
                                         BigDecimal qty,
                                         long tradeTsMs) {

        long n = seq.incrementAndGet();

        String ex = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf = normTf(timeframe);

        if (ex == null || networkType == null || chatId <= 0 || strategyType == null || sym == null) {
            return new MarketPushResult(n, false, false, false, null);
        }

        if (price == null || price.signum() <= 0 || tradeTsMs <= 0) {
            return new MarketPushResult(n, false, false, false, null);
        }

        publishSafe(new MarketTickEvent(ex, networkType, chatId, strategyType, sym, tf, price, qty, tradeTsMs));

        boolean pushedCandle = false;
        boolean createdCandle = false;
        UnifiedKline candleClosed = null;

        long tfMs = parseTimeframeMs(tf);
        if (tfMs > 0) {
            long openTime = (tradeTsMs / tfMs) * tfMs;

            double p = safeDouble(price);
            double v = (qty != null ? Math.max(0.0, safeDouble(qty)) : 0.0);

            CandleStoreKey key = new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf);
            Deque<Candle> deque = candleStorage.computeIfAbsent(key, __ -> new ConcurrentLinkedDeque<>());

            Candle lastNow;

            synchronized (deque) {
                Candle last = deque.peekLast();

                if (last == null) {
                    Candle c = new Candle(openTime, p, p, p, p, v, false);
                    deque.addLast(c);

                    createdCandle = true;
                    pushedCandle = true;

                    log.info("🕯 [STREAM] NEW CANDLE FROM AGG chatId={} type={} ex={} net={} {} {} openTime={} reason=first_tick price={} qty={}",
                            chatId,
                            strategyType,
                            ex,
                            networkType,
                            sym,
                            tf,
                            openTime,
                            price.stripTrailingZeros().toPlainString(),
                            qty != null ? qty.stripTrailingZeros().toPlainString() : "0");
                } else if (last.getTime() == openTime) {
                    double open = last.getOpen();
                    double high = Math.max(last.getHigh(), p);
                    double low = Math.min(last.getLow(), p);
                    double vol = last.getVolume() + v;

                    Candle c = new Candle(openTime, open, high, low, p, vol, false);
                    deque.pollLast();
                    deque.addLast(c);

                    pushedCandle = true;
                } else if (last.getTime() < openTime) {
                    Candle prevClosed = new Candle(
                            last.getTime(),
                            last.getOpen(),
                            last.getHigh(),
                            last.getLow(),
                            last.getClose(),
                            last.getVolume(),
                            true
                    );
                    deque.pollLast();
                    deque.addLast(prevClosed);

                    candleClosed = UnifiedKline.builder()
                            .openTime(prevClosed.getTime())
                            .closeTime(prevClosed.getTime() + tfMs - 1)
                            .open(BigDecimal.valueOf(prevClosed.getOpen()))
                            .high(BigDecimal.valueOf(prevClosed.getHigh()))
                            .low(BigDecimal.valueOf(prevClosed.getLow()))
                            .close(BigDecimal.valueOf(prevClosed.getClose()))
                            .volume(BigDecimal.valueOf(prevClosed.getVolume()))
                            .timeframe(tf)
                            .symbol(sym)
                            .closed(true)
                            .build();

                    publishSafe(new CandleClosedEvent(ex, networkType, chatId, strategyType, sym, tf, candleClosed));

                    Candle c = new Candle(openTime, p, p, p, p, v, false);
                    deque.addLast(c);

                    createdCandle = true;
                    pushedCandle = true;

                    log.info("🕯 [STREAM] NEW CANDLE FROM AGG chatId={} type={} ex={} net={} {} {} openTime={} prevOpenTime={} reason=rollover price={} qty={}",
                            chatId,
                            strategyType,
                            ex,
                            networkType,
                            sym,
                            tf,
                            openTime,
                            prevClosed.getTime(),
                            price.stripTrailingZeros().toPlainString(),
                            qty != null ? qty.stripTrailingZeros().toPlainString() : "0");

                    log.info("🕯 [STREAM] CLOSED CANDLE FROM AGG chatId={} type={} ex={} net={} {} {} openTime={} closeTime={} close={} volume={}",
                            chatId,
                            strategyType,
                            ex,
                            networkType,
                            sym,
                            tf,
                            candleClosed.getOpenTime(),
                            candleClosed.getCloseTime(),
                            candleClosed.getClose().stripTrailingZeros().toPlainString(),
                            candleClosed.getVolume().stripTrailingZeros().toPlainString());

                    while (deque.size() > MAX_CANDLES) {
                        deque.pollFirst();
                    }
                } else {
                    log.debug("⏭️ [STREAM] LATE AGG_TRADE IGNORED chatId={} type={} ex={} net={} {} {} tickOpenTime={} lastOpenTime={} price={} qty={} tradeTs={}",
                            chatId,
                            strategyType,
                            ex,
                            networkType,
                            sym,
                            tf,
                            openTime,
                            last.getTime(),
                            price.stripTrailingZeros().toPlainString(),
                            qty != null ? qty.stripTrailingZeros().toPlainString() : "0",
                            tradeTsMs);
                }

                while (deque.size() > MAX_CANDLES) {
                    deque.pollFirst();
                }

                lastNow = deque.peekLast();
            }

            if (lastNow != null) {
                pushToStreamManager(ex, networkType, sym, tf, lastNow);
            }
        }

        return new MarketPushResult(n, true, pushedCandle, createdCandle, candleClosed);
    }

    public void pushKline(String exchange,
                          NetworkType networkType,
                          long chatId,
                          StrategyType strategyType,
                          String symbol,
                          String timeframe,
                          UnifiedKline kline) {

        if (kline == null || strategyType == null) return;

        String ex = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf = normTf(timeframe);

        if (ex == null || networkType == null || sym == null || tf == null) return;

        if (kline.getSymbol() == null || kline.getSymbol().isBlank()) kline.setSymbol(sym);
        if (kline.getTimeframe() == null || kline.getTimeframe().isBlank()) kline.setTimeframe(tf);

        if (kline.isClosed()) {
            publishSafe(new CandleClosedEvent(ex, networkType, chatId, strategyType, sym, tf, kline));
        }

        Candle candle = toCandleSafe(kline);
        if (candle == null) return;

        onCandle(chatId, strategyType, ex, networkType, sym, tf, candle);
    }

    public void pushKline(String exchange,
                          NetworkType networkType,
                          long chatId,
                          StrategyType strategyType,
                          UnifiedKline kline) {

        if (kline == null || strategyType == null) return;

        String ex = normExchange(exchange);
        if (ex == null || networkType == null) return;

        String sym = normSymbol(kline.getSymbol());
        String tf = normTf(kline.getTimeframe());

        if (sym == null || tf == null) return;

        pushKline(ex, networkType, chatId, strategyType, sym, tf, kline);
    }

    public record MarketPushResult(
            long seq,
            boolean pushedTick,
            boolean pushedCandle,
            boolean createdCandle,
            UnifiedKline candleClosed
    ) {}

    // =====================================================================
    // Подписка на свечи и live ticks
    // =====================================================================

    public void subscribeCandles(String exchange,
                                 NetworkType networkType,
                                 long chatId,
                                 StrategyType strategyType,
                                 String symbol,
                                 String timeframe) {

        String ex = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf = normTf(timeframe);

        if (ex == null || networkType == null || strategyType == null || sym == null || tf == null) {
            log.warn("⚠️ [STREAM] subscribeCandles пропуск: chatId={} type={} ex={} net={} symbol={} tf={}",
                    chatId, strategyType, exchange, networkType, symbol, timeframe);
            return;
        }

        dropOtherSubscriptionsSameType(chatId, strategyType, ex, networkType, sym, tf);

        candleStorage.computeIfAbsent(
                new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf),
                __ -> new ConcurrentLinkedDeque<>()
        );

        Set<SubscriptionKey> subs =
                activeSubscriptions.computeIfAbsent(chatId, __ -> ConcurrentHashMap.newKeySet());

        SubscriptionKey key = new SubscriptionKey(strategyType, ex, networkType, sym, tf);
        boolean added = subs.add(key);

        try {
            switch (ex) {
                case "BINANCE" -> subscribeBinanceChannels(networkType, sym, tf, chatId, strategyType, added, ex);
                case "BYBIT" -> subscribeBybitChannels(networkType, sym, tf, chatId, strategyType, added, ex);
                default -> {
                    if (added) {
                        rollbackFailedSubscription(subs, key, chatId, strategyType, ex, networkType, sym, tf);
                    }
                    log.warn("⚠️ [STREAM] subscribeCandles: биржа '{}' пока не подключена для WS", ex);
                }
            }
        } catch (Exception e) {
            rollbackFailedSubscription(subs, key, chatId, strategyType, ex, networkType, sym, tf);
            log.error("❌ [STREAM] SUBSCRIBE FAILED chatId={} type={} ex={} net={} {} {} err={}",
                    chatId, strategyType, ex, networkType, sym, tf, e.getMessage(), e);
        }
    }

    private void subscribeBinanceChannels(NetworkType networkType,
                                          String sym,
                                          String tf,
                                          long chatId,
                                          StrategyType strategyType,
                                          boolean added,
                                          String ex) {

        BinanceSpotWebSocketClient ws = binanceWsProvider.getIfAvailable();
        if (ws == null) {
            throw new IllegalStateException("BINANCE ws client отсутствует");
        }

        boolean enableBookTickerForThisStrategy = isBookTickerEnabledForStrategy(strategyType);

        ws.subscribeKline(networkType, sym, tf, chatId, strategyType);
        ws.subscribeAggTrade(networkType, sym, tf, chatId, strategyType);

        if (enableBookTickerForThisStrategy) {
            ws.subscribeBookTicker(networkType, sym, chatId, strategyType);
        } else {
            try {
                ws.unsubscribeBookTicker(networkType, sym, chatId, strategyType);
            } catch (Exception ignored) {
            }
        }

        if (added) {
            log.info("📡 [STREAM] SUBSCRIBE WS: chatId={} type={} ex={} net={} {} {} ({})",
                    chatId,
                    strategyType,
                    ex,
                    networkType,
                    sym,
                    tf,
                    enableBookTickerForThisStrategy ? "KLINE+AGGTRADE+BOOK_TICKER" : "KLINE+AGGTRADE");
        } else {
            log.info("🔁 [STREAM] REUSE WS: chatId={} type={} ex={} net={} {} {} bookTicker={}",
                    chatId,
                    strategyType,
                    ex,
                    networkType,
                    sym,
                    tf,
                    enableBookTickerForThisStrategy);
        }
    }

    private void subscribeBybitChannels(NetworkType networkType,
                                        String sym,
                                        String tf,
                                        long chatId,
                                        StrategyType strategyType,
                                        boolean added,
                                        String ex) {

        BybitMarketStreamAdapter ws = bybitWsProvider.getIfAvailable();
        if (ws == null) {
            throw new IllegalStateException("BYBIT ws client отсутствует");
        }

        boolean enableBookTickerForThisStrategy = isBookTickerEnabledForStrategy(strategyType);

        ws.subscribeKline(networkType, sym, tf, chatId, strategyType);
        ws.subscribeAggTrade(networkType, sym, tf, chatId, strategyType);

        if (enableBookTickerForThisStrategy) {
            ws.subscribeBookTicker(networkType, sym, chatId, strategyType);
        } else {
            try {
                ws.unsubscribeBookTicker(networkType, sym, chatId, strategyType);
            } catch (Exception ignored) {
            }
        }

        if (added) {
            log.info("📡 [STREAM] SUBSCRIBE WS: chatId={} type={} ex={} net={} {} {} ({})",
                    chatId,
                    strategyType,
                    ex,
                    networkType,
                    sym,
                    tf,
                    enableBookTickerForThisStrategy ? "KLINE+AGGTRADE+BOOK_TICKER" : "KLINE+AGGTRADE");
        } else {
            log.info("🔁 [STREAM] REUSE WS: chatId={} type={} ex={} net={} {} {} bookTicker={}",
                    chatId,
                    strategyType,
                    ex,
                    networkType,
                    sym,
                    tf,
                    enableBookTickerForThisStrategy);
        }
    }

    private void unsubscribeBinanceChannels(NetworkType networkType,
                                            String sym,
                                            String tf,
                                            long chatId,
                                            StrategyType strategyType) {
        BinanceSpotWebSocketClient ws = binanceWsProvider.getIfAvailable();
        if (ws == null) return;

        try { ws.unsubscribeKline(networkType, sym, tf, chatId, strategyType); } catch (Exception ignored) {}
        try { ws.unsubscribeAggTrade(networkType, sym, tf, chatId, strategyType); } catch (Exception ignored) {}
        try { ws.unsubscribeBookTicker(networkType, sym, chatId, strategyType); } catch (Exception ignored) {}
    }

    private void unsubscribeBybitChannels(NetworkType networkType,
                                          String sym,
                                          String tf,
                                          long chatId,
                                          StrategyType strategyType) {
        BybitMarketStreamAdapter ws = bybitWsProvider.getIfAvailable();
        if (ws == null) return;

        try { ws.unsubscribeKline(networkType, sym, tf, chatId, strategyType); } catch (Exception ignored) {}
        try { ws.unsubscribeAggTrade(networkType, sym, tf, chatId, strategyType); } catch (Exception ignored) {}
        try { ws.unsubscribeBookTicker(networkType, sym, chatId, strategyType); } catch (Exception ignored) {}
    }

    private void rollbackFailedSubscription(Set<SubscriptionKey> subs,
                                            SubscriptionKey key,
                                            long chatId,
                                            StrategyType strategyType,
                                            String ex,
                                            NetworkType networkType,
                                            String sym,
                                            String tf) {
        if (subs != null) {
            subs.remove(key);
            if (subs.isEmpty()) {
                activeSubscriptions.remove(chatId, subs);
            }
        }
        candleStorage.remove(new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf));
        clearLateKlineLogState(chatId, strategyType, ex, networkType, sym, tf);
    }

    private void dropOtherSubscriptionsSameType(long chatId,
                                                StrategyType type,
                                                String ex,
                                                NetworkType net,
                                                String sym,
                                                String tf) {

        Set<SubscriptionKey> subs = activeSubscriptions.get(chatId);
        if (subs == null || subs.isEmpty()) return;

        SubscriptionKey keep = new SubscriptionKey(type, ex, net, sym, tf);

        for (SubscriptionKey k : List.copyOf(subs)) {
            if (k == null) continue;
            if (k.strategyType() != type) continue;
            if (k.equals(keep)) continue;

            try {
                unsubscribe(k.exchange(), k.networkType(), chatId, k.strategyType(), k.symbol(), k.timeframe());
            } catch (Exception ignored) {
            }
        }
    }

    // =====================================================================
    // HEALTH / DEGRADED
    // =====================================================================

    public SubscriptionHealth getSubscriptionHealth(long chatId,
                                                    StrategyType strategyType,
                                                    String exchange,
                                                    NetworkType networkType,
                                                    String symbol,
                                                    String timeframe) {

        String ex = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf = normTf(timeframe);

        if (chatId <= 0 || strategyType == null || ex == null || networkType == null || sym == null || tf == null) {
            return new SubscriptionHealth(false, false, false, false, -1L, -1L, -1L, true, "invalid_args");
        }

        SubscriptionKey subKey = new SubscriptionKey(strategyType, ex, networkType, sym, tf);
        Set<SubscriptionKey> subs = activeSubscriptions.get(chatId);
        boolean subscribed = subs != null && subs.contains(subKey);

        if (!subscribed) {
            return new SubscriptionHealth(false, false, false, false, -1L, -1L, -1L, true, "not_subscribed");
        }

        return switch (ex) {
            case "BINANCE" -> resolveBinanceHealth(chatId, strategyType, networkType, sym, tf);
            case "BYBIT" -> resolveBybitHealth(chatId, strategyType, networkType, sym, tf);
            default -> new SubscriptionHealth(true, false, false, false, -1L, -1L, -1L, false, "health_unsupported_exchange");
        };
    }

    private SubscriptionHealth resolveBinanceHealth(long chatId,
                                                    StrategyType strategyType,
                                                    NetworkType networkType,
                                                    String sym,
                                                    String tf) {
        BinanceSpotWebSocketClient ws = binanceWsProvider.getIfAvailable();
        if (ws == null) {
            return new SubscriptionHealth(true, false, false, false, -1L, -1L, -1L, true, "ws_client_missing");
        }

        String wsSym = sym.toLowerCase(Locale.ROOT);
        String wsTf = tf.toLowerCase(Locale.ROOT);

        boolean klineConnected = ws.isConnected(chatId, strategyType, networkType, wsSym, wsTf, "KLINE");
        boolean aggConnected = ws.isConnected(chatId, strategyType, networkType, wsSym, wsTf, "AGG_TRADE");
        boolean bookConnected = ws.isConnected(chatId, strategyType, networkType, wsSym, wsTf, "BOOK_TICKER");

        long now = System.currentTimeMillis();
        long klineAge = ageMs(ws.getLastMessageAt(buildBinanceWsKeyKline(chatId, strategyType, networkType, wsSym, wsTf)), now);
        long aggAge = ageMs(ws.getLastMessageAt(buildBinanceWsKeyAgg(chatId, strategyType, networkType, wsSym)), now);
        long bookAge = ageMs(ws.getLastMessageAt(buildBinanceWsKeyBook(chatId, strategyType, networkType, wsSym)), now);

        return buildHealth(klineConnected, aggConnected, bookConnected, klineAge, aggAge, bookAge);
    }

    private SubscriptionHealth resolveBybitHealth(long chatId,
                                                  StrategyType strategyType,
                                                  NetworkType networkType,
                                                  String sym,
                                                  String tf) {
        BybitMarketStreamAdapter ws = bybitWsProvider.getIfAvailable();
        if (ws == null) {
            return new SubscriptionHealth(true, false, false, false, -1L, -1L, -1L, true, "ws_client_missing");
        }

        String wsSym = sym.toUpperCase(Locale.ROOT);
        String wsTf = tf.toLowerCase(Locale.ROOT);

        boolean klineConnected = ws.isConnected(chatId, strategyType, networkType, wsSym, wsTf, "KLINE");
        boolean aggConnected = ws.isConnected(chatId, strategyType, networkType, wsSym, wsTf, "AGG_TRADE");
        boolean bookConnected = ws.isConnected(chatId, strategyType, networkType, wsSym, wsTf, "BOOK_TICKER");

        long now = System.currentTimeMillis();
        long klineAge = ageMs(ws.getLastMessageAt(buildBybitWsKeyKline(chatId, strategyType, networkType, wsSym, wsTf)), now);
        long aggAge = ageMs(ws.getLastMessageAt(buildBybitWsKeyAgg(chatId, strategyType, networkType, wsSym)), now);
        long bookAge = ageMs(ws.getLastMessageAt(buildBybitWsKeyBook(chatId, strategyType, networkType, wsSym)), now);

        return buildHealth(klineConnected, aggConnected, bookConnected, klineAge, aggAge, bookAge);
    }

    private SubscriptionHealth buildHealth(boolean klineConnected,
                                           boolean aggConnected,
                                           boolean bookConnected,
                                           long klineAge,
                                           long aggAge,
                                           long bookAge) {

        long silence = Math.max(5_000L, maxSilenceMs);
        long relaxedKlineSilence = Math.max(silence, 90_000L);

        boolean klineFresh = klineConnected && isFresh(klineAge, silence);
        boolean aggFresh = aggConnected && isFresh(aggAge, silence);
        boolean bookFresh = bookConnected && isFresh(bookAge, silence);

        boolean fastOk = aggFresh || bookFresh;
        boolean fastConnected = aggConnected || bookConnected;
        boolean fastPendingWarmup = fastConnected
                                    && !fastOk
                                    && ((aggConnected && aggAge < 0) || (bookConnected && bookAge < 0));

        // Если быстрый канал живой, а KLINE отстаёт, это не деградация рынка.
        // Свеча уже может поддерживаться из AGG_TRADE, а закрытый KLINE прийти позже.
        boolean klineSoftFresh = !klineFresh
                                 && klineConnected
                                 && fastOk
                                 && (klineAge < 0 || isFresh(klineAge, relaxedKlineSilence));
        boolean effectiveKlineFresh = klineFresh || klineSoftFresh;

        boolean allowFastWarmup = requireFastChannel
                                  && effectiveKlineFresh
                                  && fastPendingWarmup
                                  && Math.max(1_000L, fastChannelWarmupMs) > 0;

        boolean anyFresh = effectiveKlineFresh || fastOk;
        boolean degraded = !anyFresh && !allowFastWarmup;

        String reason;
        if (!klineConnected && !aggConnected && !bookConnected) {
            reason = "all_channels_disconnected";
            degraded = true;
        } else if (!anyFresh && !allowFastWarmup) {
            reason = "all_channels_stale";
            degraded = true;
        } else if (!effectiveKlineFresh && fastOk) {
            reason = (klineAge < 0 ? "kline_warming_up_fast_ok" : "kline_stale_fast_ok");
            degraded = false;
        } else if (effectiveKlineFresh && !fastOk && allowFastWarmup) {
            reason = "fast_channels_warming_up";
            degraded = false;
        } else if (effectiveKlineFresh && !fastOk) {
            reason = requireFastChannel ? "fast_channels_stale_kline_ok" : "kline_only";
            degraded = false;
        } else {
            reason = "ok";
        }

        return new SubscriptionHealth(
                true,
                klineConnected,
                aggConnected,
                bookConnected,
                klineAge,
                aggAge,
                bookAge,
                degraded,
                reason
        );
    }

    public boolean isDegraded(long chatId,
                              StrategyType strategyType,
                              String exchange,
                              NetworkType networkType,
                              String symbol,
                              String timeframe) {
        return getSubscriptionHealth(chatId, strategyType, exchange, networkType, symbol, timeframe).degraded();
    }

    // =====================================================================
    // CALLBACK ДЛЯ LIVE СВЕЧЕЙ
    // =====================================================================

    public void onCandle(long chatId,
                         StrategyType strategyType,
                         String exchange,
                         NetworkType networkType,
                         String symbol,
                         String timeframe,
                         Candle candle) {

        String ex = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf = normTf(timeframe);

        if (ex == null || networkType == null || strategyType == null || sym == null || tf == null || candle == null) {
            return;
        }

        CandleStoreKey key = new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf);
        Deque<Candle> deque = candleStorage.computeIfAbsent(key, __ -> new ConcurrentLinkedDeque<>());

        boolean newBucket = false;
        boolean closedUpdate = false;
        boolean lateBucket = false;
        boolean lateClosedApplied = false;
        long prevOpenTime = -1L;
        Candle storedCandle = null;

        synchronized (deque) {
            Candle last = deque.peekLast();

            if (last == null) {
                newBucket = true;
                storedCandle = candle;
            } else {
                prevOpenTime = last.getTime();

                if (last.getTime() == candle.getTime()) {
                    Candle merged = mergeCandles(last, candle);
                    deque.pollLast();
                    deque.addLast(merged);
                    storedCandle = merged;
                    if (merged.isClosed() && !last.isClosed()) {
                        closedUpdate = true;
                    }
                } else if (last.getTime() < candle.getTime()) {
                    newBucket = true;
                    storedCandle = candle;
                } else {
                    lateBucket = true;
                }
            }

            if (lateBucket) {
                if (candle.isClosed()) {
                    Candle lateMerged = applyLateClosedKlineLocked(deque, candle);
                    if (lateMerged != null) {
                        lateClosedApplied = true;
                        lateBucket = false;
                        storedCandle = lateMerged;
                    }
                }

                if (lateBucket) {
                    logLateKlineIgnored(
                            chatId,
                            strategyType,
                            ex,
                            networkType,
                            sym,
                            tf,
                            candle.getTime(),
                            prevOpenTime,
                            candle.isClosed()
                    );
                    return;
                }
            }

            if (!lateClosedApplied && storedCandle == candle) {
                deque.addLast(candle);
            }

            while (deque.size() > MAX_CANDLES) {
                deque.pollFirst();
            }
        }

        Candle candleForManager = storedCandle != null ? storedCandle : candle;
        pushToStreamManager(ex, networkType, sym, tf, candleForManager);

        if (lateClosedApplied) {
            clearLateKlineLogState(chatId, strategyType, ex, networkType, sym, tf);
            log.info("🕯 [STREAM] LATE CLOSED KLINE APPLIED chatId={} type={} ex={} net={} {} {} candleOpenTime={} lastOpenTime={} close={} volume={}",
                    chatId,
                    strategyType,
                    ex,
                    networkType,
                    sym,
                    tf,
                    candleForManager.getTime(),
                    prevOpenTime,
                    BigDecimal.valueOf(candleForManager.getClose()).stripTrailingZeros().toPlainString(),
                    BigDecimal.valueOf(candleForManager.getVolume()).stripTrailingZeros().toPlainString());
            return;
        }

        if (newBucket || closedUpdate) {
            clearLateKlineLogState(chatId, strategyType, ex, networkType, sym, tf);
        }

        if (newBucket) {
            log.info("🕯 [STREAM] NEW CANDLE FROM KLINE chatId={} type={} ex={} net={} {} {} openTime={} closed={} o={} h={} l={} c={} v={}",
                    chatId,
                    strategyType,
                    ex,
                    networkType,
                    sym,
                    tf,
                    candleForManager.getTime(),
                    candleForManager.isClosed(),
                    BigDecimal.valueOf(candleForManager.getOpen()).stripTrailingZeros().toPlainString(),
                    BigDecimal.valueOf(candleForManager.getHigh()).stripTrailingZeros().toPlainString(),
                    BigDecimal.valueOf(candleForManager.getLow()).stripTrailingZeros().toPlainString(),
                    BigDecimal.valueOf(candleForManager.getClose()).stripTrailingZeros().toPlainString(),
                    BigDecimal.valueOf(candleForManager.getVolume()).stripTrailingZeros().toPlainString());
        } else if (closedUpdate) {
            log.info("🕯 [STREAM] KLINE CLOSED UPDATE chatId={} type={} ex={} net={} {} {} openTime={} close={} volume={}",
                    chatId,
                    strategyType,
                    ex,
                    networkType,
                    sym,
                    tf,
                    candleForManager.getTime(),
                    BigDecimal.valueOf(candleForManager.getClose()).stripTrailingZeros().toPlainString(),
                    BigDecimal.valueOf(candleForManager.getVolume()).stripTrailingZeros().toPlainString());
        } else if (log.isDebugEnabled()) {
            log.debug("🕯 [STREAM] KLINE UPDATE chatId={} type={} ex={} net={} {} {} openTime={} close={}",
                    chatId,
                    strategyType,
                    ex,
                    networkType,
                    sym,
                    tf,
                    candleForManager.getTime(),
                    BigDecimal.valueOf(candleForManager.getClose()).stripTrailingZeros().toPlainString());
        }
    }

    // =====================================================================
    // ПУБЛИЧНЫЙ API ДЛЯ БЭКТЕСТА/ТЮНЕРА (КЭШ С LIMIT)
    // =====================================================================

    public List<Candle> getCachedCandles(long chatId,
                                         StrategyType strategyType,
                                         String exchange,
                                         NetworkType networkType,
                                         String symbol,
                                         String timeframe,
                                         int limit) {

        String ex = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf = normTf(timeframe);

        if (chatId <= 0 || strategyType == null || ex == null || networkType == null || sym == null || tf == null) {
            return List.of();
        }

        Deque<Candle> deque = candleStorage.get(new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf));
        if (deque != null && !deque.isEmpty()) {
            return copyTail(deque, limit);
        }

        if (streamManager != null) {
            return streamManager.getCandles(ex, networkType, sym, tf, limit);
        }

        return List.of();
    }

    public List<Candle> getCachedCandles(long chatId,
                                         StrategyType strategyType,
                                         String symbol,
                                         String timeframe,
                                         int limit) {

        String sym = normSymbol(symbol);
        String tf = normTf(timeframe);

        if (chatId <= 0 || strategyType == null || sym == null || tf == null) return List.of();

        Deque<Candle> best = null;
        int bestSize = 0;

        for (Map.Entry<CandleStoreKey, Deque<Candle>> e : candleStorage.entrySet()) {
            CandleStoreKey k = e.getKey();
            if (k == null) continue;
            if (k.chatId() != chatId) continue;
            if (k.strategyType() != strategyType) continue;
            if (!sym.equals(k.symbol())) continue;
            if (!tf.equals(k.timeframe())) continue;

            Deque<Candle> d = e.getValue();
            if (d == null) continue;

            int size = d.size();
            if (size > bestSize) {
                bestSize = size;
                best = d;
            }
        }

        if (best != null && !best.isEmpty()) {
            return copyTail(best, limit);
        }

        if (streamManager != null) {
            return streamManager.getCandles(sym, tf, limit);
        }

        return List.of();
    }

    public List<Candle> getCandles(long chatId,
                                   StrategyType strategyType,
                                   String exchange,
                                   NetworkType networkType,
                                   String symbol,
                                   String timeframe,
                                   int limit) {
        return getCachedCandles(chatId, strategyType, exchange, networkType, symbol, timeframe, limit);
    }

    public List<Candle> getCandles(long chatId,
                                   StrategyType strategyType,
                                   String exchange,
                                   NetworkType networkType,
                                   String symbol,
                                   String timeframe) {

        String ex = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf = normTf(timeframe);

        if (ex == null || networkType == null || strategyType == null || sym == null || tf == null) return List.of();

        Deque<Candle> deque = candleStorage.get(new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf));
        if (deque == null || deque.isEmpty()) return List.of();

        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    public void putCandles(long chatId,
                           StrategyType strategyType,
                           String exchange,
                           NetworkType networkType,
                           String symbol,
                           String timeframe,
                           List<Candle> candles) {

        String ex = normExchange(exchange);
        String sym = normSymbol(symbol);
        String tf = normTf(timeframe);

        if (ex == null || networkType == null || strategyType == null || sym == null || tf == null) return;

        CandleStoreKey key = new CandleStoreKey(chatId, strategyType, ex, networkType, sym, tf);
        Deque<Candle> deque = candleStorage.computeIfAbsent(key, __ -> new ConcurrentLinkedDeque<>());

        synchronized (deque) {
            deque.clear();
            if (candles != null && !candles.isEmpty()) {
                ArrayList<Candle> ordered = new ArrayList<>(candles.size());
                for (Candle c : candles) {
                    if (c == null) continue;
                    upsertOrderedCandle(ordered, c);
                }
                for (Candle c : ordered) {
                    deque.addLast(c);
                    while (deque.size() > MAX_CANDLES) deque.pollFirst();
                }
            }
        }

        if (candles != null && !candles.isEmpty()) {
            for (Candle c : candles) {
                if (c == null) continue;
                pushToStreamManager(ex, networkType, sym, tf, c);
            }
        }

        log.info("📦 [STREAM] Cache initialized: {} candles для chatId={} type={} ex={} net={} {} {}",
                deque.size(), chatId, strategyType, ex, networkType, sym, tf);
    }

    public synchronized void unsubscribeAll(long chatId) {

        Set<SubscriptionKey> subs = activeSubscriptions.get(chatId);
        if (subs == null || subs.isEmpty()) {
            candleStorage.keySet().removeIf(k -> k.chatId() == chatId);
            return;
        }

        for (SubscriptionKey key : List.copyOf(subs)) {
            try {
                unsubscribe(key.exchange(), key.networkType(), chatId, key.strategyType(), key.symbol(), key.timeframe());
            } catch (Exception ignored) {
            }
        }

        activeSubscriptions.remove(chatId);
        candleStorage.keySet().removeIf(k -> k.chatId() == chatId);
        lateKlineLogStates.keySet().removeIf(k -> k.startsWith(chatId + "|"));

        log.info("🧹 [STREAM] UNSUBSCRIBE ALL for chatId={}", chatId);
    }

    // =====================================================================
    // internals: copy tail
    // =====================================================================

    private List<Candle> copyTail(Deque<Candle> deque, int limit) {
        if (deque == null || deque.isEmpty()) return List.of();

        int lim = Math.max(1, limit);

        synchronized (deque) {
            int size = deque.size();
            if (size <= lim) return new ArrayList<>(deque);

            ArrayList<Candle> out = new ArrayList<>(lim);
            Iterator<Candle> it = deque.descendingIterator();
            while (it.hasNext() && out.size() < lim) out.add(it.next());
            Collections.reverse(out);
            return out;
        }
    }

    private void logLateKlineIgnored(long chatId,
                                      StrategyType strategyType,
                                      String exchange,
                                      NetworkType networkType,
                                      String symbol,
                                      String timeframe,
                                      long candleOpenTime,
                                      long lastOpenTime,
                                      boolean closed) {

        String stateKey = buildLateKlineStateKey(chatId, strategyType, exchange, networkType, symbol, timeframe, closed);
        LateKlineLogState state = lateKlineLogStates.computeIfAbsent(stateKey, __ -> new LateKlineLogState());

        long now = System.currentTimeMillis();
        long throttleMs = Math.max(1_000L, lateKlineLogThrottleMs);
        long seen = state.incrementAndGet();
        long lastLoggedAt = state.lastLoggedAtMs();

        boolean shouldLog = seen == 1
                || now - lastLoggedAt >= throttleMs
                || (lateKlineSampleEvery > 0 && seen % Math.max(1, lateKlineSampleEvery) == 0);

        if (!shouldLog) {
            return;
        }

        if (!state.tryMarkLogged(now, throttleMs, seen)) {
            return;
        }

        long suppressed = state.drainSeen();
        String baseMsg = "chatId={} type={} ex={} net={} {} {} candleOpenTime={} lastOpenTime={} closed={} repeats={}";

        if (closed) {
            log.warn("⏭️ [STREAM] LATE CLOSED KLINE IGNORED " + baseMsg,
                    chatId, strategyType, exchange, networkType, symbol, timeframe, candleOpenTime, lastOpenTime, true, suppressed);
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("⏭️ [STREAM] late open kline ignored " + baseMsg,
                    chatId, strategyType, exchange, networkType, symbol, timeframe, candleOpenTime, lastOpenTime, false, suppressed);
        } else if (suppressed > 1) {
            log.info("⏭️ [STREAM] late open kline ignored " + baseMsg,
                    chatId, strategyType, exchange, networkType, symbol, timeframe, candleOpenTime, lastOpenTime, false, suppressed);
        }
    }

    private void clearLateKlineLogState(long chatId,
                                        StrategyType strategyType,
                                        String exchange,
                                        NetworkType networkType,
                                        String symbol,
                                        String timeframe) {
        lateKlineLogStates.remove(buildLateKlineStateKey(chatId, strategyType, exchange, networkType, symbol, timeframe, false));
        lateKlineLogStates.remove(buildLateKlineStateKey(chatId, strategyType, exchange, networkType, symbol, timeframe, true));
    }

    private String buildLateKlineStateKey(long chatId,
                                          StrategyType strategyType,
                                          String exchange,
                                          NetworkType networkType,
                                          String symbol,
                                          String timeframe,
                                          boolean closed) {
        return chatId + "|" + strategyType + "|" + exchange + "|" + networkType + "|" + symbol + "|" + timeframe + "|" + (closed ? "closed" : "open");
    }

    // =====================================================================
    // MarketStreamManager
    // =====================================================================

    private void pushToStreamManager(String exchange, NetworkType network, String symbol, String timeframe, Candle candle) {
        if (streamManager == null || candle == null) return;
        try {
            streamManager.addCandle(exchange, network, symbol, timeframe, candle);
        } catch (Exception ignored) {
        }
    }

    // =====================================================================
    // events
    // =====================================================================

    private void publishSafe(Object event) {
        if (eventPublisher == null || event == null) return;
        try {
            eventPublisher.publishEvent(event);
        } catch (Exception ignored) {
        }
    }

    // =====================================================================
    // ordered candle helpers
    // =====================================================================

    private AggTradeApplyResult applyAggTradeOrdered(List<Candle> list,
                                                     long openTime,
                                                     long tfMs,
                                                     double price,
                                                     double volume,
                                                     String symbol,
                                                     String timeframe) {

        if (list.isEmpty()) {
            Candle created = new Candle(openTime, price, price, price, price, volume, false);
            list.add(created);
            trimToMax(list);
            return new AggTradeApplyResult(true, true, null, created);
        }

        int existingIdx = indexOfTime(list, openTime);
        if (existingIdx >= 0) {
            Candle existing = list.get(existingIdx);

            double open = existing.getOpen();
            double high = Math.max(existing.getHigh(), price);
            double low = Math.min(existing.getLow(), price);
            double close = price;
            double vol = existing.getVolume() + volume;

            Candle updated = new Candle(
                    existing.getTime(),
                    open,
                    high,
                    low,
                    close,
                    vol,
                    existing.isClosed()
            );
            list.set(existingIdx, updated);
            trimToMax(list);

            Candle last = list.get(list.size() - 1);
            return new AggTradeApplyResult(true, false, null, last);
        }

        Candle tail = list.get(list.size() - 1);

        if (tail.getTime() < openTime) {
            UnifiedKline closed = null;

            if (!tail.isClosed()) {
                Candle prevClosed = new Candle(
                        tail.getTime(),
                        tail.getOpen(),
                        tail.getHigh(),
                        tail.getLow(),
                        tail.getClose(),
                        tail.getVolume(),
                        true
                );
                list.set(list.size() - 1, prevClosed);

                closed = UnifiedKline.builder()
                        .openTime(prevClosed.getTime())
                        .closeTime(prevClosed.getTime() + tfMs - 1)
                        .open(BigDecimal.valueOf(prevClosed.getOpen()))
                        .high(BigDecimal.valueOf(prevClosed.getHigh()))
                        .low(BigDecimal.valueOf(prevClosed.getLow()))
                        .close(BigDecimal.valueOf(prevClosed.getClose()))
                        .volume(BigDecimal.valueOf(prevClosed.getVolume()))
                        .timeframe(timeframe)
                        .symbol(symbol)
                        .closed(true)
                        .build();
            }

            Candle created = new Candle(openTime, price, price, price, price, volume, false);
            list.add(created);
            trimToMax(list);

            Candle last = list.get(list.size() - 1);
            return new AggTradeApplyResult(true, true, closed, last);
        }

        // out-of-order старый тик: вставляем в правильное место
        Candle created = new Candle(openTime, price, price, price, price, volume, false);
        int insertPos = insertionIndex(list, openTime);
        list.add(insertPos, created);
        trimToMax(list);

        Candle last = list.get(list.size() - 1);
        return new AggTradeApplyResult(true, true, null, last);
    }

    private Candle applyLateClosedKlineLocked(Deque<Candle> deque, Candle candle) {
        if (deque == null || candle == null || !candle.isClosed()) {
            return null;
        }

        ArrayList<Candle> ordered = new ArrayList<>(deque);
        Candle merged = upsertOrderedCandle(ordered, candle);

        if (ordered.isEmpty()) {
            return null;
        }

        deque.clear();
        for (Candle item : ordered) {
            if (item != null) {
                deque.addLast(item);
            }
        }
        return merged;
    }

    private Candle upsertOrderedCandle(List<Candle> list, Candle candle) {
        if (candle == null) return null;

        if (list.isEmpty()) {
            list.add(candle);
            trimToMax(list);
            return candle;
        }

        int idx = indexOfTime(list, candle.getTime());
        if (idx >= 0) {
            Candle merged = mergeCandles(list.get(idx), candle);
            list.set(idx, merged);
            trimToMax(list);
            return merged;
        }

        int insertPos = insertionIndex(list, candle.getTime());
        list.add(insertPos, candle);
        trimToMax(list);
        return candle;
    }

    private Candle mergeCandles(Candle existing, Candle incoming) {
        if (existing == null) return incoming;
        if (incoming == null) return existing;

        long time = incoming.getTime() > 0 ? incoming.getTime() : existing.getTime();

        double open = existing.getOpen();
        if (open <= 0.0) {
            open = incoming.getOpen();
        }

        double incomingHigh = incoming.getHigh() > 0.0 ? incoming.getHigh() : incoming.getClose();
        double incomingLow = incoming.getLow() > 0.0 ? incoming.getLow() : incoming.getClose();
        double existingHigh = existing.getHigh() > 0.0 ? existing.getHigh() : existing.getClose();
        double existingLow = existing.getLow() > 0.0 ? existing.getLow() : existing.getClose();

        double high = Math.max(existingHigh, incomingHigh);
        double low = Math.min(existingLow, incomingLow);
        double close = incoming.getClose() > 0.0 ? incoming.getClose() : existing.getClose();
        double volume = Math.max(existing.getVolume(), incoming.getVolume());
        boolean closed = existing.isClosed() || incoming.isClosed();

        return new Candle(time, open, high, low, close, volume, closed);
    }

    private int indexOfTime(List<Candle> list, long time) {
        for (int i = 0; i < list.size(); i++) {
            Candle c = list.get(i);
            if (c != null && c.getTime() == time) return i;
        }
        return -1;
    }

    private int insertionIndex(List<Candle> list, long time) {
        for (int i = 0; i < list.size(); i++) {
            Candle c = list.get(i);
            if (c != null && c.getTime() > time) {
                return i;
            }
        }
        return list.size();
    }

    private void trimToMax(List<Candle> list) {
        while (list.size() > MAX_CANDLES) {
            list.remove(0);
        }
    }

    private void rewriteDeque(Deque<Candle> deque, List<Candle> list) {
        deque.clear();
        for (Candle c : list) {
            if (c != null) {
                deque.addLast(c);
            }
        }
    }

    private record AggTradeApplyResult(
            boolean pushedCandle,
            boolean createdCandle,
            UnifiedKline candleClosed,
            Candle lastCandle
    ) {}

    private static final class LateKlineLogState {
        private final AtomicLong seen = new AtomicLong(0);
        private final AtomicLong lastLoggedAtMs = new AtomicLong(0);

        long incrementAndGet() {
            return seen.incrementAndGet();
        }

        long lastLoggedAtMs() {
            return lastLoggedAtMs.get();
        }

        boolean tryMarkLogged(long now, long throttleMs, long currentSeen) {
            while (true) {
                long prev = lastLoggedAtMs.get();
                if (prev > 0 && now - prev < throttleMs && currentSeen > 1) {
                    return false;
                }
                if (lastLoggedAtMs.compareAndSet(prev, now)) {
                    return true;
                }
            }
        }

        long drainSeen() {
            long value = seen.getAndSet(0);
            return Math.max(value, 1L);
        }
    }

    // =====================================================================
    // keys + нормализация
    // =====================================================================

    private record SubscriptionKey(StrategyType strategyType,
                                   String exchange,
                                   NetworkType networkType,
                                   String symbol,
                                   String timeframe) {}

    private record CandleStoreKey(long chatId,
                                  StrategyType strategyType,
                                  String exchange,
                                  NetworkType networkType,
                                  String symbol,
                                  String timeframe) {}

    private static String normSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normTf(String timeframe) {
        if (timeframe == null) return null;
        String s = timeframe.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normExchange(String exchange) {
        if (exchange == null) return null;
        String s = exchange.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    // =====================================================================
    // timeframe parser
    // =====================================================================

    private static long parseTimeframeMs(String tf) {
        if (tf == null) return -1;
        String s = tf.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || "na".equals(s)) return -1;

        long mult;
        char unit = s.charAt(s.length() - 1);

        String numStr = s.substring(0, s.length() - 1).trim();
        if (numStr.isEmpty()) return -1;

        long n;
        try {
            n = Long.parseLong(numStr);
        } catch (Exception e) {
            return -1;
        }

        if (n <= 0) return -1;

        if (unit == 's') mult = 1_000L;
        else if (unit == 'm') mult = 60_000L;
        else if (unit == 'h') mult = 3_600_000L;
        else if (unit == 'd') mult = 86_400_000L;
        else if (unit == 'w') mult = 604_800_000L;
        else return -1;

        long ms = n * mult;
        return ms > 0 ? ms : -1;
    }

    private static double safeDouble(BigDecimal v) {
        try {
            return v.doubleValue();
        } catch (Exception e) {
            return 0.0;
        }
    }

    // =====================================================================
    // UnifiedKline -> Candle
    // =====================================================================

    private Candle toCandleSafe(UnifiedKline kline) {
        if (kline == null) return null;

        long openTime = kline.getOpenTime();
        BigDecimal o = kline.getOpen();
        BigDecimal h = kline.getHigh();
        BigDecimal l = kline.getLow();
        BigDecimal c = kline.getClose();
        BigDecimal v = kline.getVolume();

        if (openTime <= 0 || o == null || h == null || l == null || c == null) {
            return null;
        }

        boolean closed = kline.isClosed();

        double od = safeDouble(o);
        double hd = safeDouble(h);
        double ld = safeDouble(l);
        double cd = safeDouble(c);
        double vd = (v != null ? safeDouble(v) : 0.0);

        return new Candle(openTime, od, hd, ld, cd, vd, closed);
    }

    // =====================================================================
    // WS health helpers
    // =====================================================================

    private static boolean isFresh(long ageMs, long maxSilenceMs) {
        return ageMs >= 0 && ageMs <= maxSilenceMs;
    }

    private static long ageMs(Long lastTs, long now) {
        if (lastTs == null || lastTs <= 0L) return -1L;
        long age = now - lastTs;
        return Math.max(age, 0L);
    }

    private static String buildBinanceWsKeyAgg(long chatId,
                                               StrategyType strategyType,
                                               NetworkType net,
                                               String symLower) {
        return "BINANCE:" + net + ":" + chatId + ":" + strategyType.name() + ":" + symLower + ":AGG_TRADE";
    }

    private static String buildBinanceWsKeyBook(long chatId,
                                                StrategyType strategyType,
                                                NetworkType net,
                                                String symLower) {
        return "BINANCE:" + net + ":" + chatId + ":" + strategyType.name() + ":" + symLower + ":BOOK_TICKER";
    }

    private static String buildBinanceWsKeyKline(long chatId,
                                                 StrategyType strategyType,
                                                 NetworkType net,
                                                 String symLower,
                                                 String tfLower) {
        return "BINANCE:" + net + ":" + chatId + ":" + strategyType.name() + ":" + symLower + ":" + tfLower + ":KLINE";
    }

    private static String buildBybitWsKeyAgg(long chatId,
                                             StrategyType strategyType,
                                             NetworkType net,
                                             String symUpper) {
        return "BYBIT:" + net + ":" + chatId + ":" + strategyType.name() + ":" + symUpper + ":AGG_TRADE";
    }

    private static String buildBybitWsKeyBook(long chatId,
                                              StrategyType strategyType,
                                              NetworkType net,
                                              String symUpper) {
        return "BYBIT:" + net + ":" + chatId + ":" + strategyType.name() + ":" + symUpper + ":BOOK_TICKER";
    }

    private static String buildBybitWsKeyKline(long chatId,
                                               StrategyType strategyType,
                                               NetworkType net,
                                               String symUpper,
                                               String tfLower) {
        return "BYBIT:" + net + ":" + chatId + ":" + strategyType.name() + ":" + symUpper + ":" + tfLower + ":KLINE";
    }

    private boolean isBookTickerEnabledForStrategy(StrategyType strategyType) {
        if (!bookTickerEnabled) {
            return false;
        }
        return strategyType != StrategyType.WINDOW_SCALPING || windowScalpingBookTickerEnabled;
    }
}


