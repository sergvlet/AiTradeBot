package com.chicu.aitradebot.strategy.windowscalping;

import com.chicu.aitradebot.ai.runtime.MlAutoTuneRuntime;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import com.chicu.aitradebot.trade.PositionStore;
import com.chicu.aitradebot.trade.TradeExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@StrategyBinding(StrategyType.WINDOW_SCALPING)
@Slf4j
@Component
@RequiredArgsConstructor
public class WindowScalpingStrategyV4 implements
        TradingStrategy,
        AiStrategyOrchestrator.PriceUpdateAware,
        AiStrategyOrchestrator.CandleCloseAware {

    @Value("${strategy.window.settingsRefreshSeconds:10}")
    private long settingsRefreshSeconds;

    @Value("${strategy.window.tickLogEveryTicks:800}")
    private long tickLogEveryTicks;

    @Value("${strategy.window.holdThrottleMs:2500}")
    private long holdThrottleMs;

    // === ML gate ===
    @Value("${strategy.window.mlEnabled:true}")
    private boolean mlEnabled;

    /**
     * ✅ КЛЮЧЕВОЕ: если ML недоступен — не блокируем торговлю.
     * true  -> ML "fail-open": если predict недоступен, продолжаем без ML-гейта
     * false -> ML "fail-closed": predict_failed => HOLD и запрет входа
     */
    @Value("${strategy.window.mlFailOpen:true}")
    private boolean mlFailOpen;

    // fallback-порог, если ss.mlConfidence == null
    @Value("${strategy.window.mlMinProba:0.60}")
    private double mlMinProba;

    // === AUTO-TUNE ON HOLD ===
    @Value("${strategy.window.autoTuneOnHold:true}")
    private boolean autoTuneOnHold;

    @Value("${strategy.window.autoTuneHoldCooldownSeconds:60}")
    private long autoTuneHoldCooldownSeconds;

    @Value("${strategy.window.autoTuneHoldReasons:range_too_small,windowSize<5,no_settings,window_invalid,range_zero,pos_invalid}")
    private String autoTuneHoldReasons;

    // =====================================================
    // ✅ COARSE-ADJUST (локальный, персистентный)
    // =====================================================

    @Value("${strategy.window.coarseAdjustEnabled:true}")
    private boolean coarseAdjustEnabled;

    @Value("${strategy.window.coarseAdjustAfterConsecutive:6}")
    private int coarseAdjustAfterConsecutive;

    @Value("${strategy.window.coarseAdjustCooldownSeconds:120}")
    private long coarseAdjustCooldownSeconds;

    @Value("${strategy.window.coarseAdjustFactor:0.85}")
    private double coarseAdjustFactor;

    @Value("${strategy.window.coarseAdjustMinFloorPct:0.02}")
    private double coarseAdjustMinFloorPct;

    // =====================================================

    private final StrategyLivePublisher live;
    private final WindowScalpingStrategySettingsService windowSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    private final ApplicationContext appContext;
    private final MlAutoTuneRuntime autoTuneRuntime;
    private final PositionStore positionStore;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        WindowScalpingStrategySettings cfg;

        String symbol;
        String exchange;
        NetworkType network;

        Instant lastSettingsLoadAt;
        String lastFingerprint;

        Deque<BigDecimal> window = new ArrayDeque<>();

        boolean inPosition;
        boolean isLong;

        BigDecimal entryPrice;
        BigDecimal tp;
        BigDecimal sl;
        BigDecimal entryQty;
        Long entryOrderId;

        Instant lastTradeClosedAt;
        Instant lastEntryAt;

        long ticks;
        long warmups;
        long entries;
        long exits;

        String lastHoldReason;
        Instant lastHoldAt;

        Instant lastDiagAt;
        Instant lastAutoTuneRequestAt;

        // ✅ для coarse-adjust
        int consecutiveRangeTooSmall;
        Instant lastCoarseAdjustAt;
    }

    // =====================================================
    // START / STOP
    // =====================================================

    @Override
    public void start(Long chatId, String symbolHint) {
        start(chatId, symbolHint, null, null);
    }

    @Override
    public void start(Long chatId, String symbolHint, String exchange, NetworkType network) {

        String hintEx = normalizeExchangeOrNull(exchange);

        StrategySettings ss = loadStrategySettingsAuto(chatId, hintEx, network);
        WindowScalpingStrategySettings cfg = windowSettingsService.getOrCreate(chatId);

        LocalState st = new LocalState();
        st.active = true;
        st.startedAt = Instant.now();

        st.ss = ss;
        st.cfg = cfg;

        st.exchange = normalizeExchangeOrNull(ss != null ? ss.getExchangeName() : hintEx);
        st.network  = ss != null ? ss.getNetworkType() : network;

        String sym = ss != null ? normalizeSymbolOrNull(ss.getSymbol()) : null;
        if (sym == null) sym = normalizeSymbolOrNull(symbolHint);
        st.symbol = sym;

        st.lastSettingsLoadAt = Instant.now();
        st.lastFingerprint = buildFingerprint(ss, cfg);

        st.inPosition = false;
        st.isLong = true;
        st.entryPrice = null;
        st.tp = null;
        st.sl = null;
        st.entryQty = null;
        st.entryOrderId = null;
        st.lastTradeClosedAt = null;
        st.lastEntryAt = null;
        st.lastAutoTuneRequestAt = null;

        st.consecutiveRangeTooSmall = 0;
        st.lastCoarseAdjustAt = null;

        states.put(chatId, st);

        ensureRuntimeContext(st, ss);

        // ✅ FIX: авто-тюн стартуем только когда точно известны exchange+network
        if (st.exchange != null && st.network != null) {
            safeAutoTune(() -> autoTuneRuntime.onStrategyStarted(chatId, StrategyType.WINDOW_SCALPING, st.exchange, st.network));
        } else {
            log.warn("[WINDOW] 🧠 skip autoTuneRuntime.onStrategyStarted (нет exchange/network) chatId={} ex={} net={}",
                    chatId, st.exchange, st.network);
        }

        if (ss != null) {
            log.info("[WINDOW] 🎯 Выбраны StrategySettings: id={} active={} биржа={} сеть={} символ={} updatedAt={}",
                    ss.getId(),
                    ss.isActive(),
                    ss.getExchangeName(),
                    ss.getNetworkType(),
                    ss.getSymbol(),
                    ss.getUpdatedAt()
            );
        } else {
            log.warn("[WINDOW] ⚠ StrategySettings не найден/не загружен (chatId={}) — стратегия будет стоять в HOLD до появления настроек.", chatId);
        }

        log.info("[WINDOW] ▶ СТАРТ chatId={} биржа={} сеть={} символ={} окно={} вход_от_низа%={} мин_диапазон%={} TP%={} SL%={} ML={} ML_failOpen={} ML_min(fallback)={} autoTuneOnHold={} holdTuneCooldownSec={} coarseAdjustEnabled={} coarseAfter={} coarseCdSec={} coarseFactor={} coarseMinFloorPct={}",
                chatId,
                st.exchange,
                st.network,
                st.symbol,
                cfg != null ? cfg.getWindowSize() : null,
                cfg != null ? cfg.getEntryFromLowPct() : null,
                cfg != null ? cfg.getMinRangePct() : null,
                cfg != null ? cfg.getTakeProfitPct() : null,
                cfg != null ? cfg.getStopLossPct() : null,
                mlEnabled,
                mlFailOpen,
                fmt(mlMinProba),
                autoTuneOnHold,
                autoTuneHoldCooldownSeconds,
                coarseAdjustEnabled,
                coarseAdjustAfterConsecutive,
                coarseAdjustCooldownSeconds,
                fmt(coarseAdjustFactor),
                fmt(coarseAdjustMinFloorPct)
        );

        if (st.symbol != null) {
            safeLive(() -> live.pushState(chatId, StrategyType.WINDOW_SCALPING, st.symbol, true));
            safeLive(() -> live.pushSignal(chatId, StrategyType.WINDOW_SCALPING, st.symbol, null, Signal.hold("Стратегия запущена")));
        }
    }

    @Override
    public void stop(Long chatId, String ignored) {
        stop(chatId, ignored, null, null);
    }

    @Override
    public void stop(Long chatId, String ignored, String exchange, NetworkType network) {

        LocalState st = states.remove(chatId);
        if (st == null) return;

        ensureRuntimeContext(st, st.ss);
        st.lastEntryAt = null;

        safePositionStoreClose(chatId, st);

        // ✅ FIX: стоп авто-тюна тоже только если контекст валидный
        if (st.exchange != null && st.network != null) {
            safeAutoTune(() -> autoTuneRuntime.onStrategyStopped(chatId, StrategyType.WINDOW_SCALPING, st.exchange, st.network));
        }

        final String sym = st.symbol;

        if (sym != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.WINDOW_SCALPING, sym));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.WINDOW_SCALPING, sym));
            safeLive(() -> live.pushState(chatId, StrategyType.WINDOW_SCALPING, sym, false));
        }

        log.info("[WINDOW] ⏹ СТОП chatId={} биржа={} сеть={} символ={} тики={} прогрев={} входы={} выходы={} в_позиции={}",
                chatId, st.exchange, st.network, sym, st.ticks, st.warmups, st.entries, st.exits, st.inPosition);
    }

    @Override
    public boolean isActive(Long chatId) {
        LocalState st = states.get(chatId);
        return st != null && st.active;
    }

    @Override
    public Instant getStartedAt(Long chatId) {
        LocalState st = states.get(chatId);
        return st != null ? st.startedAt : null;
    }

    // =====================================================
    // ORCHESTRATOR HOOKS
    // =====================================================

    @Override
    public void onPriceUpdate(long chatId,
                              StrategyType type,
                              String symbol,
                              String timeframe,
                              BigDecimal price,
                              long tradeTsMs,
                              String exchange,
                              NetworkType network) {

        if (type != StrategyType.WINDOW_SCALPING) return;

        LocalState st = states.get(chatId);
        if (st != null) {
            String ex = normalizeExchangeOrNull(exchange);
            if (ex != null) st.exchange = ex;
            if (network != null) st.network = network;

            String sym = normalizeSymbolOrNull(symbol);
            if (sym != null) st.symbol = sym;
        }

        Instant ts = (tradeTsMs > 0)
                ? Instant.ofEpochMilli(tradeTsMs)
                : Instant.now();

        onPriceUpdate(chatId, symbol, price, ts);
    }

    @Override
    public void onCandleClosed(long chatId,
                               StrategyType type,
                               String symbol,
                               String timeframe,
                               UnifiedKline kline,
                               String exchange,
                               NetworkType network) {

        if (type != StrategyType.WINDOW_SCALPING) return;
        if (kline == null) return;

        BigDecimal close = extractClosePriceSafe(kline);
        if (close == null || close.signum() <= 0) return;

        long tsMs = extractKlineCloseTimeMsSafe(kline);
        Instant ts = (tsMs > 0) ? Instant.ofEpochMilli(tsMs) : Instant.now();

        LocalState st = states.get(chatId);
        if (st != null) {
            String ex = normalizeExchangeOrNull(exchange);
            if (ex != null) st.exchange = ex;
            if (network != null) st.network = network;

            String sym = normalizeSymbolOrNull(symbol);
            if (sym != null) st.symbol = sym;
        }

        onPriceUpdate(chatId, symbol, close, ts);
    }

    // =====================================================
    // PRICE UPDATE (CORE)
    // =====================================================

    @Override
    public void onPriceUpdate(Long chatId, String symbolFromTick, BigDecimal price, Instant ts) {

        LocalState st = states.get(chatId);
        if (st == null || !st.active) return;

        st.ticks++;

        long logEvery = Math.max(1, tickLogEveryTicks);
        long holdMs   = Math.max(200, holdThrottleMs);

        if (price == null || price.signum() <= 0) {
            if (st.ticks % logEvery == 0) {
                log.warn("[WINDOW] ⚠ Некорректная цена: chatId={} price={}", chatId, price);
            }
            return;
        }

        Instant time = (ts != null ? ts : Instant.now());

        String tickSymbol = normalizeSymbolOrNull(symbolFromTick);
        String cfgSymbol  = normalizeSymbolOrNull(st.symbol);

        if (cfgSymbol != null && tickSymbol != null && !cfgSymbol.equals(tickSymbol)) return;
        if (cfgSymbol == null && tickSymbol != null) st.symbol = tickSymbol;

        final String symLive = normalizeSymbolOrNull(st.symbol);
        if (symLive != null) {
            safeLive(() -> live.pushPriceTick(chatId, StrategyType.WINDOW_SCALPING, symLive, price, time));
        }

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            StrategySettings ss = st.ss;
            WindowScalpingStrategySettings cfg = st.cfg;
            String sym = normalizeSymbolOrNull(st.symbol);

            if (sym == null || cfg == null || ss == null) {
                pushHoldThrottled(chatId, sym, st, "no_settings", time, holdMs);
                diagLogOccasionally(chatId, st, sym, price, "Нет настроек (StrategySettings/CFG/символ).", logEvery, time);
                return;
            }

            ensureRuntimeContext(st, ss);

            int windowSize = (cfg.getWindowSize() != null ? cfg.getWindowSize() : 0);
            if (windowSize < 5) {
                pushHoldThrottled(chatId, sym, st, "windowSize<5", time, holdMs);
                diagLogOccasionally(chatId, st, sym, price, "Окно слишком маленькое (нужно >= 5).", logEvery, time);
                return;
            }

            st.window.addLast(price);
            while (st.window.size() > windowSize) st.window.removeFirst();

            if (st.window.size() < windowSize) {
                st.warmups++;
                pushHoldThrottled(chatId, sym, st, "warming_up", time, holdMs);
                if (st.ticks % logEvery == 0) {
                    log.info("[WINDOW] ⏳ Прогрев окна: chatId={} символ={} размер_окна={}/{} цена={}",
                            chatId, sym, st.window.size(), windowSize, price.stripTrailingZeros().toPlainString());
                }
                return;
            }

            BigDecimal high = null;
            BigDecimal low  = null;
            for (BigDecimal p : st.window) {
                if (p == null) continue;
                high = (high == null) ? p : high.max(p);
                low  = (low  == null) ? p : low.min(p);
            }

            if (high == null || low == null || low.signum() <= 0) {
                pushHoldThrottled(chatId, sym, st, "window_invalid", time, holdMs);
                diagLogOccasionally(chatId, st, sym, price, "Окно некорректно (low/high не рассчитались).", logEvery, time);
                return;
            }

            BigDecimal range = high.subtract(low);
            if (range.signum() <= 0) {
                pushHoldThrottled(chatId, sym, st, "range_zero", time, holdMs);
                diagLogOccasionally(chatId, st, sym, price, "Диапазон равен нулю (high==low).", logEvery, time);
                return;
            }

            double rangePct = range
                    .divide(low, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();

            double minRangePct = (cfg.getMinRangePct() != null ? cfg.getMinRangePct() : 0.0);
            if (rangePct + 1e-12 < minRangePct) {
                pushHoldThrottled(chatId, sym, st, "range_too_small", time, holdMs);
                if (st.ticks % logEvery == 0) {
                    log.info("[WINDOW] 💤 Диапазон слишком мал: chatId={} символ={} rangePct={} < minRangePct={} (consecutive={}/{})",
                            chatId, sym, fmt(rangePct), fmt(minRangePct),
                            st.consecutiveRangeTooSmall, Math.max(2, coarseAdjustAfterConsecutive));
                }
                return;
            }

            // ✅ если диапазон ок — сбрасываем счётчик проблемного холда
            st.consecutiveRangeTooSmall = 0;

            double pos = price.subtract(low)
                    .divide(range, 10, RoundingMode.HALF_UP)
                    .doubleValue();

            if (Double.isNaN(pos) || Double.isInfinite(pos)) {
                pushHoldThrottled(chatId, sym, st, "pos_invalid", time, holdMs);
                diagLogOccasionally(chatId, st, sym, price, "Позиция в окне (pos) некорректна (NaN/Inf).", logEvery, time);
                return;
            }

            double entryLowPct  = (cfg.getEntryFromLowPct()  != null ? cfg.getEntryFromLowPct()  : 0.0);
            double entryHighPct = (cfg.getEntryFromHighPct() != null ? cfg.getEntryFromHighPct() : 0.0);

            double lowZone  = clamp01(entryLowPct / 100.0);
            double highZone = clamp01(1.0 - (entryHighPct / 100.0));

            if (log.isDebugEnabled() && st.ticks % logEvery == 0) {
                log.debug("[WINDOW] tick chatId={} символ={} цена={} low={} high={} rangePct={} posPct={}",
                        chatId,
                        sym,
                        price.stripTrailingZeros().toPlainString(),
                        low.stripTrailingZeros().toPlainString(),
                        high.stripTrailingZeros().toPlainString(),
                        fmt(rangePct),
                        fmt(pos * 100.0));
            }

            // =====================================================
            // ENTRY (SPOT LONG)
            // =====================================================
            if (!st.inPosition && pos <= lowZone) {

                Integer cooldown = ss.getCooldownSeconds();
                if (cooldown != null && cooldown > 0 && st.lastTradeClosedAt != null) {
                    long passed = Duration.between(st.lastTradeClosedAt, time).getSeconds();
                    if (passed < cooldown) {
                        pushHoldThrottled(chatId, sym, st, "cooldown", time, holdMs);
                        return;
                    }
                }

                final double score = clamp01(
                        (lowZone <= 0.000001) ? 1.0 : (1.0 - (pos / lowZone))
                ) * 100.0;

                BigDecimal diffPctForEntry = BigDecimal.valueOf(Math.max(0.000001, (lowZone - pos) * 100.0));

                BigDecimal tpPct = cfg.getTakeProfitPct();
                BigDecimal slPct = cfg.getStopLossPct();
                if (tpPct == null || tpPct.signum() <= 0 || slPct == null || slPct.signum() <= 0) {
                    pushHoldThrottled(chatId, sym, st, "tp_sl_pct_invalid", time, holdMs);
                    return;
                }

                // =====================================================
                // ✅ ML gate (fail-open)
                // =====================================================
                if (mlEnabled) {
                    double threshold = resolveMlThreshold(ss);

                    Map<String, Object> feats = buildMlFeatures(
                            chatId, st, sym, price, time,
                            low, high, range, rangePct, pos,
                            lowZone, highZone, windowSize, diffPctForEntry
                    );

                    Prediction pred = tryPredict(feats);

                    if (!pred.ok) {
                        if (mlFailOpen) {
                            if (st.ticks % logEvery == 0) {
                                log.warn("[WINDOW] 🤖 ML недоступен (fail-open): chatId={} символ={} причина={} => продолжаю без ML-гейта",
                                        chatId, sym, pred.reason);
                            }
                        } else {
                            log.warn("[WINDOW] 🤖 ML-прогноз недоступен (fail-closed): chatId={} символ={} причина={}", chatId, sym, pred.reason);
                            pushHoldThrottled(chatId, sym, st, "predict_failed", time, holdMs);
                            return;
                        }
                    } else {
                        if (st.ticks % logEvery == 0) {
                            log.info("[WINDOW] 🤖 ML-прогноз: chatId={} символ={} модель={} вероятность_покупки={}",
                                    chatId, sym, pred.modelKey, fmt(pred.proba));
                        }

                        if (pred.proba + 1e-12 < threshold) {
                            pushHoldThrottled(chatId, sym, st, "ml_below_threshold", time, holdMs);
                            return;
                        }
                    }
                }

                try {
                    var res = tradeExecutionService.executeEntry(
                            chatId,
                            StrategyType.WINDOW_SCALPING,
                            sym,
                            price,
                            diffPctForEntry,
                            time,
                            ss,
                            tpPct,
                            slPct
                    );

                    if (!res.executed()) {
                        pushHoldThrottled(chatId, sym, st, res.reason(), time, holdMs);
                        return;
                    }

                    st.entries++;
                    st.inPosition = true;
                    st.isLong = true;

                    st.entryPrice = res.entryPrice();
                    st.tp = res.tp();
                    st.sl = res.sl();
                    st.entryQty = res.qty();
                    st.entryOrderId = res.orderId();

                    st.lastEntryAt = time;

                    ensureRuntimeContext(st, ss);
                    safePositionStoreOpen(chatId, st);

                    if (st.tp != null && st.sl != null) {
                        safeLive(() -> live.pushTpSl(chatId, StrategyType.WINDOW_SCALPING, sym, st.tp, st.sl));
                    }

                    safeLive(() -> live.pushSignal(chatId, StrategyType.WINDOW_SCALPING, sym, null,
                            Signal.buy(score, "Вход у нижней границы окна")));

                    st.window.clear();
                    st.lastHoldReason = null;
                    st.consecutiveRangeTooSmall = 0;

                } catch (Exception e) {
                    log.error("[WINDOW] ❌ Ошибка входа: chatId={} символ={} err={}", chatId, sym, e.getMessage(), e);
                    pushHoldThrottled(chatId, sym, st, "entry_failed", time, holdMs);
                    return;
                }
            }

            // =====================================================
            // EXIT: TP/SL
            // =====================================================
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {

                if (st.lastEntryAt != null && Duration.between(st.lastEntryAt, time).toMillis() < 500) {
                    return;
                }

                try {
                    // ✅ у тебя в TradeExecutionServiceImpl метод принимает exchange+network
                    var ex = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.WINDOW_SCALPING,
                            sym,
                            price,
                            time,
                            true,
                            st.entryQty,
                            st.tp,
                            st.sl,
                            st.exchange,
                            st.network
                    );

                    if (ex.executed()) {
                        st.exits++;

                        st.inPosition = false;
                        st.entryQty = null;
                        st.entryOrderId = null;
                        st.entryPrice = null;
                        st.tp = null;
                        st.sl = null;

                        st.lastTradeClosedAt = time;
                        st.lastEntryAt = null;

                        ensureRuntimeContext(st, ss);

                        safePositionStoreClose(chatId, st);

                        if (st.exchange != null && st.network != null) {
                            safeAutoTune(() -> autoTuneRuntime.onPositionClosed(
                                    chatId,
                                    StrategyType.WINDOW_SCALPING,
                                    st.exchange,
                                    st.network
                            ));
                        }

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.WINDOW_SCALPING, sym));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.WINDOW_SCALPING, sym));

                        safeLive(() -> live.pushSignal(chatId, StrategyType.WINDOW_SCALPING, sym, null,
                                Signal.sell(1.0, "Выход по TP/SL")));
                    } else {
                        if (pos >= highZone) {
                            pushHoldThrottled(chatId, sym, st, "in_high_zone_wait_tp", time, holdMs);
                        }
                    }

                } catch (Exception e) {
                    log.error("[WINDOW] ❌ Ошибка выхода: chatId={} символ={} err={}", chatId, sym, e.getMessage(), e);
                }
            }

            if (st.ticks % logEvery == 0) {
                log.info("[WINDOW] 📋 Диагностика: chatId={} символ={} биржа={} сеть={} тики={} окно={}/{} в_позиции={} входы={} выходы={} последний_HOLD={}",
                        chatId,
                        sym,
                        st.exchange,
                        st.network,
                        st.ticks,
                        st.window.size(),
                        (cfg.getWindowSize() != null ? cfg.getWindowSize() : -1),
                        st.inPosition,
                        st.entries,
                        st.exits,
                        (st.lastHoldReason != null ? (holdReasonRu(st.lastHoldReason) + " [" + st.lastHoldReason + "]") : "-")
                );
            }
        }
    }

    private void diagLogOccasionally(Long chatId, LocalState st, String symbol, BigDecimal price, String msg, long logEvery, Instant now) {
        if (logEvery <= 0) return;
        if (st.ticks % logEvery != 0) return;
        log.info("[WINDOW] 🩺 Диагностика: chatId={} символ={} цена={} => {}",
                chatId,
                symbol,
                price != null ? price.stripTrailingZeros().toPlainString() : "null",
                msg
        );
        st.lastDiagAt = now;
    }

    // =====================================================
    // SETTINGS REFRESH
    // =====================================================

    private void refreshSettingsIfNeeded(Long chatId, LocalState st, Instant now) {

        Duration refreshEvery = Duration.ofSeconds(Math.max(1, settingsRefreshSeconds));

        if (st.lastSettingsLoadAt != null &&
            Duration.between(st.lastSettingsLoadAt, now).compareTo(refreshEvery) < 0) {
            return;
        }

        try {
            StrategySettings loaded = loadStrategySettingsAuto(chatId, st.exchange, st.network);
            WindowScalpingStrategySettings cfg = windowSettingsService.getOrCreate(chatId);

            String fp = buildFingerprint(loaded, cfg);
            boolean changed = st.lastFingerprint == null || !Objects.equals(st.lastFingerprint, fp);

            String oldSymbol = normalizeSymbolOrNull(st.symbol);

            if (loaded != null) st.ss = loaded;
            if (cfg != null) st.cfg = cfg;

            if (loaded != null) {
                String loadedSymbol = normalizeSymbolOrNull(loaded.getSymbol());
                if (loadedSymbol != null) st.symbol = loadedSymbol;

                if (loaded.getExchangeName() != null) st.exchange = normalizeExchangeOrNull(loaded.getExchangeName());
                if (loaded.getNetworkType() != null) st.network = loaded.getNetworkType();
            }

            st.lastSettingsLoadAt = now;

            if (changed) {
                st.lastFingerprint = fp;

                log.info("[WINDOW] ⚙️ Обновлены настройки: chatId={} биржа={} сеть={} символ={} окно={} вход_от_низа%={} мин_диапазон%={} TP%={} SL%={}",
                        chatId,
                        st.exchange,
                        st.network,
                        st.symbol,
                        cfg != null ? cfg.getWindowSize() : null,
                        cfg != null ? cfg.getEntryFromLowPct() : null,
                        cfg != null ? cfg.getMinRangePct() : null,
                        cfg != null ? cfg.getTakeProfitPct() : null,
                        cfg != null ? cfg.getStopLossPct() : null
                );

                String newSymbol = normalizeSymbolOrNull(st.symbol);
                if (oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol)) {
                    st.window.clear();
                    st.lastHoldReason = null;

                    st.lastEntryAt = null;
                    st.inPosition = false;
                    st.entryQty = null;
                    st.entryOrderId = null;
                    st.entryPrice = null;
                    st.tp = null;
                    st.sl = null;

                    st.consecutiveRangeTooSmall = 0;

                    safePositionStoreClose(chatId, st);

                    log.info("[WINDOW] 🔄 Символ изменился: {} -> {}, очищаю окно и сбрасываю позицию.", oldSymbol, newSymbol);
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[WINDOW] ⚠️ Не удалось обновить настройки: chatId={} msg={}", chatId, e.toString());
        }
    }

    private String buildFingerprint(StrategySettings ss, WindowScalpingStrategySettings cfg) {
        String symbol = ss != null ? normalizeSymbolOrNull(ss.getSymbol()) : null;
        String ex     = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String net    = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String tf     = ss != null ? safe(ss.getTimeframe()) : "null";
        String candles = ss != null && ss.getCachedCandlesLimit() != null ? String.valueOf(ss.getCachedCandlesLimit()) : "null";
        String cooldown = ss != null && ss.getCooldownSeconds() != null ? String.valueOf(ss.getCooldownSeconds()) : "null";

        String w = cfg != null ? String.valueOf(cfg.getWindowSize()) : "null";
        String low = cfg != null ? String.valueOf(cfg.getEntryFromLowPct()) : "null";
        String high = cfg != null ? String.valueOf(cfg.getEntryFromHighPct()) : "null";
        String minR = cfg != null ? String.valueOf(cfg.getMinRangePct()) : "null";

        String tpPct = cfg != null && cfg.getTakeProfitPct() != null ? cfg.getTakeProfitPct().stripTrailingZeros().toPlainString() : "null";
        String slPct = cfg != null && cfg.getStopLossPct() != null ? cfg.getStopLossPct().stripTrailingZeros().toPlainString() : "null";

        return (symbol != null ? symbol : "null") + "|" + ex + "|" + net + "|" + tf + "|" + candles + "|" + cooldown
               + "|" + w + "|" + low + "|" + high + "|" + minR
               + "|" + tpPct + "|" + slPct;
    }

    // =====================================================
    // STRATEGY SETTINGS LOAD (AUTO, NO HARDCODE)
    // =====================================================

    private StrategySettings loadStrategySettingsAuto(Long chatId, String exchange, NetworkType network) {
        String ex = normalizeExchangeOrNull(exchange);

        if (ex != null && network != null) {
            return strategySettingsService.getOrCreate(chatId, StrategyType.WINDOW_SCALPING, ex, network);
        }

        StrategySettings viaReflection = tryCallSettingsServiceFallback(chatId);
        if (viaReflection != null) return viaReflection;

        throw new IllegalStateException(
                "Нельзя загрузить StrategySettings для WINDOW_SCALPING без exchange/network. " +
                "Передай exchange+network в start(), либо добавь StrategySettingsService.getOrCreate(chatId, type). " +
                "(chatId=" + chatId + ")"
        );
    }

    private StrategySettings tryCallSettingsServiceFallback(Long chatId) {
        try {
            Method m = strategySettingsService.getClass().getMethod("getOrCreate", Long.class, StrategyType.class);
            Object r = m.invoke(strategySettingsService, chatId, StrategyType.WINDOW_SCALPING);
            return (r instanceof StrategySettings ss) ? ss : null;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            log.warn("[WINDOW] ⚠ fallback getOrCreate(chatId,type) упал: {}", e.toString());
        }

        try {
            Method m = strategySettingsService.getClass().getMethod("getSettingsOrThrow", Long.class, StrategyType.class);
            Object r = m.invoke(strategySettingsService, chatId, StrategyType.WINDOW_SCALPING);
            return (r instanceof StrategySettings ss) ? ss : null;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            log.warn("[WINDOW] ⚠ fallback getSettingsOrThrow(chatId,type) упал: {}", e.toString());
        }

        return null;
    }

    // =====================================================
    // CONTEXT HELPERS
    // =====================================================

    private void ensureRuntimeContext(LocalState st, StrategySettings ss) {
        if (st == null) return;

        if (st.exchange == null && ss != null && ss.getExchangeName() != null) {
            st.exchange = normalizeExchangeOrNull(ss.getExchangeName());
        } else if (st.exchange != null) {
            st.exchange = normalizeExchangeOrNull(st.exchange);
        }

        if (st.network == null && ss != null && ss.getNetworkType() != null) {
            st.network = ss.getNetworkType();
        }
    }

    // =====================================================
    // ✅ COARSE-ADJUST реализация
    // =====================================================

    private void maybeCoarseAdjustOnRangeTooSmall(Long chatId, String symbol, LocalState st, Instant now) {
        if (!coarseAdjustEnabled) return;
        if (chatId == null || st == null) return;
        if (st.inPosition) return;

        WindowScalpingStrategySettings cfg = st.cfg;
        if (cfg == null) return;

        int afterN = Math.max(2, coarseAdjustAfterConsecutive);
        if (st.consecutiveRangeTooSmall < afterN) return;

        long cd = Math.max(15, coarseAdjustCooldownSeconds);
        if (st.lastCoarseAdjustAt != null) {
            long passed = Duration.between(st.lastCoarseAdjustAt, now).getSeconds();
            if (passed < cd) return;
        }

        double factor = coarseAdjustFactor;
        if (Double.isNaN(factor) || Double.isInfinite(factor) || factor <= 0 || factor >= 1.0) {
            factor = 0.85;
        }

        double floor = coarseAdjustMinFloorPct;
        if (Double.isNaN(floor) || Double.isInfinite(floor) || floor <= 0) floor = 0.02;

        Double oldMinObj = cfg.getMinRangePct();
        double oldMin = (oldMinObj != null ? oldMinObj : 0.0);
        if (oldMin <= 0) oldMin = 0.10;

        double newMin = Math.max(floor, oldMin * factor);

        if (Math.abs(newMin - oldMin) < 1e-12) return;

        try {
            WindowScalpingStrategySettings patch = WindowScalpingStrategySettings.builder()
                    .chatId(chatId)
                    .minRangePct(newMin)
                    .build();

            windowSettingsService.update(chatId, patch);

            st.cfg = windowSettingsService.getOrCreate(chatId);
            st.lastFingerprint = buildFingerprint(st.ss, st.cfg);
            st.lastSettingsLoadAt = now;

            st.window.clear();
            st.consecutiveRangeTooSmall = 0;
            st.lastCoarseAdjustAt = now;

            log.warn("[WINDOW] 🛠️ COARSE-ADJUST применён: chatId={} символ={} minRangePct {} -> {} (afterConsecutive={} cooldown={}s)",
                    chatId,
                    symbol,
                    fmt(oldMin),
                    fmt(newMin),
                    afterN,
                    cd
            );
        } catch (Exception e) {
            log.warn("[WINDOW] ⚠️ COARSE-ADJUST не применился: chatId={} символ={} err={}", chatId, symbol, e.toString());
        }
    }

    // =====================================================
    // ML (reflection-safe)
    // =====================================================

    private static class Prediction {
        final boolean ok;
        final String modelKey;
        final double proba;
        final String reason;

        private Prediction(boolean ok, String modelKey, double proba, String reason) {
            this.ok = ok;
            this.modelKey = modelKey;
            this.proba = proba;
            this.reason = reason;
        }

        static Prediction ok(String modelKey, double proba) {
            return new Prediction(true, modelKey, proba, null);
        }

        static Prediction fail(String reason) {
            return new Prediction(false, null, 0.0, reason);
        }
    }

    private double resolveMlThreshold(StrategySettings ss) {
        if (ss != null && ss.getMlConfidence() != null) {
            double v = ss.getMlConfidence().doubleValue();
            if (!Double.isNaN(v) && !Double.isInfinite(v) && v > 0) return v;
        }
        return mlMinProba;
    }

    private Map<String, Object> buildMlFeatures(
            Long chatId,
            LocalState st,
            String symbol,
            BigDecimal price,
            Instant ts,
            BigDecimal low,
            BigDecimal high,
            BigDecimal range,
            double rangePct,
            double pos,
            double lowZone,
            double highZone,
            int windowSize,
            BigDecimal diffPctForEntry
    ) {
        Map<String, Object> f = new HashMap<>();

        f.put("chatId", chatId);
        f.put("strategy", StrategyType.WINDOW_SCALPING.name());
        f.put("symbol", symbol);
        f.put("exchange", st.exchange);
        f.put("network", st.network != null ? st.network.name() : null);
        f.put("ts", (ts != null ? ts : Instant.now()).toEpochMilli());

        f.put("windowSize", windowSize);
        f.put("price", price.doubleValue());
        f.put("low", low.doubleValue());
        f.put("high", high.doubleValue());
        f.put("range", range.doubleValue());
        f.put("rangePct", rangePct);

        f.put("pos01", pos);
        f.put("posPct", pos * 100.0);

        f.put("lowZone01", lowZone);
        f.put("highZone01", highZone);

        f.put("diffPctForEntry", diffPctForEntry.doubleValue());

        BigDecimal first = st.window.peekFirst();
        BigDecimal last = st.window.peekLast();
        if (first != null && first.signum() > 0 && last != null) {
            double retPct = last.subtract(first)
                    .divide(first, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();
            f.put("retWindowPct", retPct);
        } else {
            f.put("retWindowPct", 0.0);
        }

        return f;
    }

    private Prediction tryPredict(Map<String, Object> features) {
        try {
            Object bean = findMlBean();
            if (bean == null) return Prediction.fail("ml_bean_not_found");

            Object resp = invokePredict(bean, features);
            if (resp == null) return Prediction.fail("predict_return_null");

            Boolean ok = (Boolean) readAny(resp, "ok", "isOk", "success", "isSuccess");
            if (ok != null && !ok) {
                Object reason = readAny(resp, "reason", "message", "error");
                return Prediction.fail(reason != null ? String.valueOf(reason) : "predict_not_ok");
            }

            Double proba = readNumber(resp, "proba", "probability", "confidence", "score");
            if (proba == null) return Prediction.fail("no_proba_in_response");

            String modelKey = (String) readAny(resp, "modelKey", "modelId", "key", "model");
            if (modelKey == null) modelKey = "unknown";

            return Prediction.ok(modelKey, proba);

        } catch (Exception e) {
            return Prediction.fail("predict_exception:" + e.getClass().getSimpleName());
        }
    }

    private Object findMlBean() {
        if (appContext.containsBean("mlPredictionService")) return appContext.getBean("mlPredictionService");
        if (appContext.containsBean("mlService")) return appContext.getBean("mlService");

        String[] names = appContext.getBeanDefinitionNames();
        for (String n : names) {
            Object b;
            try { b = appContext.getBean(n); } catch (Exception ignored) { continue; }
            if (b == null) continue;
            if (hasSupportedPredictMethod(b.getClass())) return b;
        }
        return null;
    }

    private boolean hasSupportedPredictMethod(Class<?> c) {
        for (Method m : c.getMethods()) {
            String name = m.getName().toLowerCase(Locale.ROOT);
            if (!name.contains("predict")) continue;
            int pc = m.getParameterCount();
            Class<?>[] pt = m.getParameterTypes();

            if (pc == 1 && Map.class.isAssignableFrom(pt[0])) return true;
            if (pc == 5 && pt[2] == String.class && Map.class.isAssignableFrom(pt[3]) && pt[4] == Instant.class) return true;
            if (pc == 4 && pt[1] == String.class && Map.class.isAssignableFrom(pt[2]) && pt[3] == Instant.class) return true;
        }
        return false;
    }

    private Object invokePredict(Object bean, Map<String, Object> features) throws Exception {
        Class<?> c = bean.getClass();

        Long chatId = (Long) features.get("chatId");
        String symbol = (String) features.get("symbol");
        Instant ts = Instant.ofEpochMilli(((Number) features.getOrDefault("ts", System.currentTimeMillis())).longValue());

        for (Method m : c.getMethods()) {
            if (!m.getName().equals("predictWindowScalping")) continue;
            if (m.getParameterCount() != 4) continue;
            return m.invoke(bean, chatId, symbol, features, ts);
        }

        for (Method m : c.getMethods()) {
            if (!m.getName().equals("predict")) continue;
            if (m.getParameterCount() != 5) continue;
            return m.invoke(bean, StrategyType.WINDOW_SCALPING, chatId, symbol, features, ts);
        }

        for (Method m : c.getMethods()) {
            if (!m.getName().equals("predict")) continue;
            if (m.getParameterCount() != 1) continue;
            if (!Map.class.isAssignableFrom(m.getParameterTypes()[0])) continue;
            return m.invoke(bean, features);
        }

        return null;
    }

    private Object readAny(Object obj, String... gettersOrFields) {
        try {
            Class<?> c = obj.getClass();
            for (String n : gettersOrFields) {
                Method m = findNoArgMethod(c, n);
                if (m != null) return m.invoke(obj);

                try {
                    var f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Double readNumber(Object obj, String... gettersOrFields) {
        Object v = readAny(obj, gettersOrFields);
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof BigDecimal bd) return bd.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception ignored) {}
        return null;
    }

    private Method findNoArgMethod(Class<?> c, String name) {
        try { return c.getMethod(name); } catch (Exception ignored) {}
        String cap = name.length() > 0 ? Character.toUpperCase(name.charAt(0)) + name.substring(1) : name;
        try { return c.getMethod("get" + cap); } catch (Exception ignored) {}
        try { return c.getMethod("is" + cap); } catch (Exception ignored) {}
        return null;
    }

    // =====================================================
    // LIVE HELPERS + AUTO-TUNE on HOLD
    // =====================================================

    private void safeLive(Runnable r) {
        try { r.run(); } catch (Exception ignored) {}
    }

    private void safeAutoTune(Runnable r) {
        try { r.run(); } catch (Exception ignored) {}
    }

    private void safePositionStoreOpen(Long chatId, LocalState st) {
        try {
            if (chatId == null) return;
            if (st == null) return;

            String ex = normalizeExchangeOrNull(st.exchange);
            if (ex == null || st.network == null) return;

            st.exchange = ex;
            positionStore.markOpened(chatId, StrategyType.WINDOW_SCALPING, ex, st.network);
        } catch (Exception ignored) {}
    }

    private void safePositionStoreClose(Long chatId, LocalState st) {
        try {
            if (chatId == null) return;
            if (st == null) return;

            String ex = normalizeExchangeOrNull(st.exchange);
            if (ex == null || st.network == null) return;

            st.exchange = ex;
            positionStore.markClosed(chatId, StrategyType.WINDOW_SCALPING, ex, st.network);
        } catch (Exception ignored) {}
    }

    private Set<String> parsedAutoTuneHoldReasons() {
        try {
            String s = (autoTuneHoldReasons == null ? "" : autoTuneHoldReasons.trim());
            if (s.isEmpty()) return Set.of();
            String[] parts = s.split(",");
            java.util.Set<String> out = new java.util.HashSet<>();
            for (String p : parts) {
                String v = p.trim();
                if (!v.isEmpty()) out.add(v);
            }
            return out;
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private void maybeRequestAutoTuneOnHold(Long chatId, String symbol, LocalState st, String reason, Instant now) {
        if (!autoTuneOnHold) return;
        if (autoTuneRuntime == null) return;
        if (st == null) return;
        if (st.inPosition) return;

        ensureRuntimeContext(st, st.ss);

        if (st.exchange == null || st.network == null) return;

        Set<String> reasons = parsedAutoTuneHoldReasons();
        if (!reasons.isEmpty() && !reasons.contains(reason)) return;

        long cdSec = Math.max(10, autoTuneHoldCooldownSeconds);
        if (st.lastAutoTuneRequestAt != null) {
            long passed = Duration.between(st.lastAutoTuneRequestAt, now).getSeconds();
            if (passed < cdSec) return;
        }

        st.lastAutoTuneRequestAt = now;

        log.warn("[WINDOW] 🧠 AUTO-TUNE (HOLD): chatId={} символ={} причина={} => triggerTuneDebounced (cooldown={}s)",
                chatId, symbol, reason, cdSec);

        safeAutoTune(() -> autoTuneRuntime.triggerTuneDebounced(
                chatId,
                StrategyType.WINDOW_SCALPING,
                st.exchange,
                st.network,
                "hold:" + reason,
                Duration.ofSeconds(cdSec)
        ));
    }

    /**
     * ✅ FIX: больше НЕ выходим, если symbol == null.
     *  - live-сигнал пушим только если есть куда (symbol != null)
     *  - но coarse-adjust и auto-tune должны работать даже без symbol
     */
    private void pushHoldThrottled(Long chatId, String symbol, LocalState st, String reason, Instant now, long holdMs) {
        if (st == null) return;

        // symbol-safe: сначала аргумент, потом st.symbol
        String sym = normalizeSymbolOrNull(symbol);
        if (sym == null) sym = normalizeSymbolOrNull(st.symbol);

        // ✅ FIX: финальная копия для лямбд
        final String symFinal = sym;

        // ✅ считаем подряд range_too_small (для coarse-adjust)
        if ("range_too_small".equals(reason)) {
            st.consecutiveRangeTooSmall = Math.max(0, st.consecutiveRangeTooSmall) + 1;
        } else if (!"warming_up".equals(reason)) {
            st.consecutiveRangeTooSmall = 0;
        }

        if (Objects.equals(st.lastHoldReason, reason) && st.lastHoldAt != null) {
            long ms = Duration.between(st.lastHoldAt, now).toMillis();
            if (ms < holdMs) {
                if ("range_too_small".equals(reason)) {
                    maybeCoarseAdjustOnRangeTooSmall(chatId, symFinal, st, now);
                }
                maybeRequestAutoTuneOnHold(chatId, symFinal, st, reason, now);
                return;
            }
        }

        st.lastHoldReason = reason;
        st.lastHoldAt = now;

        String ru = holdReasonRu(reason);
        String msg = (ru != null ? (ru + " [" + reason + "]") : ("HOLD: " + reason));

        // live пушим только если есть символ
        if (symFinal != null) {
            safeLive(() -> live.pushSignal(chatId, StrategyType.WINDOW_SCALPING, symFinal, null, Signal.hold(msg)));
        }

        // ✅ сначала coarse-adjust, потом внешний тюнер
        if ("range_too_small".equals(reason)) {
            maybeCoarseAdjustOnRangeTooSmall(chatId, symFinal, st, now);
        }

        maybeRequestAutoTuneOnHold(chatId, symFinal, st, reason, now);
    }

    private String holdReasonRu(String code) {
        if (code == null) return null;

        return switch (code) {
            case "started" -> "Старт стратегии";
            case "no_settings" -> "Нет настроек (StrategySettings/CFG/символ)";
            case "windowSize<5" -> "Размер окна слишком мал (нужно минимум 5)";
            case "warming_up" -> "Прогрев окна (недостаточно тиков)";
            case "window_invalid" -> "Не удалось корректно построить окно (low/high)";
            case "range_zero" -> "Диапазон окна нулевой (high == low)";
            case "range_too_small" -> "Диапазон слишком мал для входа";
            case "pos_invalid" -> "Не удалось вычислить позицию цены в окне";
            case "cooldown" -> "Ожидание после сделки (cooldown)";
            case "tp_sl_pct_invalid" -> "Некорректный TP/SL (проценты должны быть > 0)";
            case "predict_failed" -> "ML-прогноз недоступен";
            case "ml_below_threshold" -> "ML-прогноз ниже порога (вход запрещён)";
            case "entry_failed" -> "Ошибка при входе в сделку";
            case "in_high_zone_wait_tp" -> "Цена у верхней границы — ждём TP";
            default -> {
                String lc = code.toLowerCase(Locale.ROOT);
                if (lc.contains("balance")) yield "Недостаточно баланса";
                if (lc.contains("notional")) yield "Слишком маленький объём (NOTIONAL)";
                if (lc.contains("min")) yield "Нарушено минимальное ограничение биржи";
                if (lc.contains("spread")) yield "Слишком большой спред";
                yield null;
            }
        };
    }

    // =====================================================
    // KLINE SAFE EXTRACT
    // =====================================================

    private BigDecimal extractClosePriceSafe(UnifiedKline kline) {
        Object v =
                tryInvokeNoArg(kline, "getClose")
                        .or(() -> tryInvokeNoArg(kline, "close"))
                        .or(() -> tryInvokeNoArg(kline, "getC"))
                        .orElse(null);

        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(String.valueOf(v).trim()); } catch (Exception ignored) {}
        return null;
    }

    private long extractKlineCloseTimeMsSafe(UnifiedKline kline) {
        Object v =
                tryInvokeNoArg(kline, "getCloseTimeMs")
                        .or(() -> tryInvokeNoArg(kline, "getCloseTime"))
                        .or(() -> tryInvokeNoArg(kline, "getEndTimeMs"))
                        .or(() -> tryInvokeNoArg(kline, "getT"))
                        .orElse(null);

        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v).trim()); } catch (Exception ignored) {}
        return 0L;
    }

    private java.util.Optional<Object> tryInvokeNoArg(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            return java.util.Optional.ofNullable(m.invoke(target));
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }

    // =====================================================
    // UTILS
    // =====================================================

    private static String safe(String s) {
        return s == null ? "null" : s.trim();
    }

    private static String normalizeSymbolOrNull(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normalizeExchangeOrNull(String exchange) {
        if (exchange == null) return null;
        String s = exchange.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
