package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.account.AccountBalanceService;
import com.chicu.aitradebot.account.AccountBalanceSnapshot;
import com.chicu.aitradebot.ai.runtime.MlAutoTuneRuntime;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.exchange.model.AccountFees;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.market.model.SymbolDescriptor;
import com.chicu.aitradebot.repository.OrderRepository;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.market.service.MarketSymbolService;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.trade.math.QtyMath;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeExecutionServiceImpl implements TradeExecutionService {

    private static final int QTY_SCALE = 8;
    private static final int PRICE_SCALE = 8;

    private static final String PHASE_COLLECT  = "COLLECT";
    private static final String PHASE_BACKTEST = "BACKTEST";
    private static final String PHASE_PAPER    = "PAPER";

    /** Мини-буфер сверх комиссий, чтобы TP не был в ноль после комиссий. */
    private static final BigDecimal TP_FEE_BUFFER_PCT = new BigDecimal("0.02");

    /**
     * Минимальный notional, ниже которого локальную позицию не восстанавливаем,
     * потому что биржа уже не даст корректно закрыть такую dust-позицию MARKET-ордером.
     */
    private static final BigDecimal MIN_RESTORABLE_NOTIONAL = new BigDecimal("5.00");

    /** Дефолтный minNotional, если биржа/OrderService не смогли вернуть значение. */
    private static final BigDecimal DEFAULT_MIN_NOTIONAL = new BigDecimal("5.00");

    /** Анти-спам для EXIT, если биржа/guard блокирует. */
    private static final String EXIT_KEY_SUFFIX = ":EXIT";

    /** Анти-спам логов по ML gate. */
    private static final long ML_GATE_LOG_THROTTLE_MS = 5_000L;

    /** На сколько блокировать повторный restore после dust-exit. */
    private static final long DEFAULT_DUST_RESTORE_SUPPRESS_MS = 21_600_000L;

    private final OrderService orderService;
    private final StrategyLivePublisher live;
    private final AccountBalanceService accountBalanceService;
    private final MarketSymbolService marketSymbolService;

    private final StrategySettingsService settingsService;
    private final TradeFailCooldownService failCooldown;
    private final PositionStore positionStore;
    private final MlAutoTuneRuntime mlAutoTuneRuntime;
    private final OrderRepository orderRepository;

    private final ApplicationEventPublisher eventPublisher;

    private final Map<String, Instant> throttledLogTimes = new ConcurrentHashMap<>();

    /** negative-cache для пустого/dust restore, чтобы не лезть в историю на каждом тике */
    private final Map<String, Instant> restoreMissUntil = new ConcurrentHashMap<>();

    /** throttle dust-логов восстановления */
    private final Map<String, Instant> dustRestoreLogTimes = new ConcurrentHashMap<>();

    @Value("${trade.position-restore.retry-cooldown-ms:30000}")
    private long restoreRetryCooldownMs;

    @Value("${trade.position-restore.dust-log-throttle-ms:60000}")
    private long dustRestoreLogThrottleMs;

    @Value("${strategy.defaults.exchange:BINANCE}")
    private String defaultExchange;

    @Value("${strategy.defaults.network:TESTNET}")
    private String defaultNetwork;

    /**
     * true  -> PAPER = симуляция на MAINNET
     * false -> PAPER = реальные ордера
     *
     * На TESTNET PAPER не блокируется этим флагом.
     */
    @Value("${trade.paperBlocksRealOrders:${trade.paper.blocksRealOrders:true}}")
    private boolean paperBlocksRealOrders;

    @Value("${trade.position-restore.dust-suppress-ms:21600000}")
    private long dustRestoreSuppressMs;

    @PostConstruct
    public void onStart() {
        log.info("✅ [Трейд] TradeExecutionServiceImpl активен | paperBlocksRealOrders={}", paperBlocksRealOrders);
    }

    private void clearDustPositionFromRuntime(Long chatId,
                                              StrategyType strategyType,
                                              String exchange,
                                              NetworkType network,
                                              String symbol,
                                              BigDecimal qty,
                                              BigDecimal price,
                                              String code,
                                              String details) {
        long suppressMs = Math.max(60_000L, dustRestoreSuppressMs > 0 ? dustRestoreSuppressMs : DEFAULT_DUST_RESTORE_SUPPRESS_MS);

        try {
            positionStore.clearPosition(chatId, strategyType, exchange, network, symbol);
        } catch (Exception ignored) {
        }

        try {
            if (positionStore instanceof InMemoryPositionStoreImpl inMemoryPositionStore) {
                inMemoryPositionStore.suppressRestore(
                        chatId,
                        strategyType,
                        exchange,
                        network,
                        symbol,
                        suppressMs,
                        code + (details != null && !details.isBlank() ? ": " + details : "")
                );
            }
        } catch (Exception ignored) {
        }

        log.warn("🧹 [Выход] Очищаю локальную позицию как dust chatId={} type={} ex={} net={} sym={} qty={} price={} code={} details={} suppressMs={}",
                chatId,
                strategyType,
                exchange,
                network,
                symbol,
                QtyMath.strip(qty),
                QtyMath.strip(price),
                code,
                details,
                suppressMs);
    }

    // =====================================================
    // ENTRY
    // =====================================================

    @Override
    public EntryResult executeEntry(Long chatId,
                                    StrategyType strategyType,
                                    String symbol,
                                    BigDecimal price,
                                    BigDecimal diffPct,
                                    Instant time,
                                    StrategySettings ss) {
        return EntryResult.fail(
                "executeEntry(chatId,type,symbol,price,diffPct,time,ss) запрещён: TP/SL должны приходить из таблицы конкретной стратегии. " +
                "Используй executeEntry(..., tpPct, slPct)."
        );
    }

    @Override
    public EntryResult executeEntry(Long chatId,
                                    StrategyType strategyType,
                                    String symbol,
                                    BigDecimal price,
                                    BigDecimal diffPct,
                                    Instant time,
                                    StrategySettings ss,
                                    BigDecimal tpPct,
                                    BigDecimal slPct) {

        if (chatId == null) return EntryResult.fail("chatId=null");
        if (strategyType == null) return EntryResult.fail("strategyType=null");
        if (ss == null) return EntryResult.fail("StrategySettings=null");

        String sym = normalizeSymbol(symbol);
        if (sym == null) return EntryResult.fail("symbol пустой");

        if (price == null || price.signum() <= 0) return EntryResult.fail("price_invalid");

        if (diffPct == null || diffPct.signum() <= 0) {
            if (log.isDebugEnabled()) {
                log.debug("⛔ [Вход] Пропуск: для SPOT вход только BUY | diffPct={} | chatId={} {} {}",
                        diffPct, chatId, strategyType, sym);
            }
            return EntryResult.fail("spot_entry_only_buy");
        }

        String ex = resolveExchange(ss);
        NetworkType net = resolveNetwork(ss);

        if (ex == null) {
            log.warn("⛔ [Вход] Пропуск: exchange не определён ни из БД, ни из defaults | chatId={} type={} sym={}",
                    chatId, strategyType, sym);
            return EntryResult.fail("exchange_not_resolved");
        }
        if (net == null) {
            log.warn("⛔ [Вход] Пропуск: network не определён ни из БД, ни из defaults | chatId={} type={} sym={}",
                    chatId, strategyType, sym);
            return EntryResult.fail("network_not_resolved");
        }

        if (!isValidPct(tpPct)) {
            log.warn("⛔ [Вход] Пропуск: takeProfitPct некорректен | tpPct={} | chatId={} {} {}",
                    tpPct, chatId, strategyType, sym);
            return EntryResult.fail("takeProfitPct_invalid");
        }
        if (!isValidPct(slPct)) {
            log.warn("⛔ [Вход] Пропуск: stopLossPct некорректен | slPct={} | chatId={} {} {}",
                    slPct, chatId, strategyType, sym);
            return EntryResult.fail("stopLossPct_invalid");
        }

        String phase = normalizeUpperNullable(ss.getRunPhase());

        if (PHASE_BACKTEST.equals(phase)) return EntryResult.fail("runPhase=BACKTEST");

        boolean collectMode = PHASE_COLLECT.equals(phase);
        boolean paperMode   = PHASE_PAPER.equals(phase);

        boolean paperBlocksNow = paperMode && paperBlocksRealOrders && net == NetworkType.MAINNET;

        MlGateDecision mlGate = evaluateMlGate(ss);

        if (mlGate.bypassed()) {
            String logKey = buildMlGateLogKey(chatId, strategyType, ex, net, sym) + ":bypass";
            if (shouldLogNow(logKey, ML_GATE_LOG_THROTTLE_MS)) {
                log.info("⚠️ [Вход] ML gate пропущен в fail-open режиме | reason={} | conf={} minProb={} | chatId={} {} {} ex={} net={}",
                        mlGate.reason(),
                        QtyMath.strip(mlGate.confidence()),
                        QtyMath.strip(mlGate.minProb()),
                        chatId, strategyType, sym, ex, net);
            }
        }

        if (mlGate.reject()) {
            String logKey = buildMlGateLogKey(chatId, strategyType, ex, net, sym);
            if (shouldLogNow(logKey, ML_GATE_LOG_THROTTLE_MS)) {
                log.info("⛔ [Вход] ML gate отклонил вход | reason={} | conf={} < minProb={} | chatId={} {} {} ex={} net={}",
                        mlGate.reason(),
                        QtyMath.strip(mlGate.confidence()),
                        QtyMath.strip(mlGate.minProb()),
                        chatId, strategyType, sym, ex, net);
            }

            safeLive(() -> live.pushSignal(
                    chatId,
                    strategyType,
                    sym,
                    null,
                    Signal.hold("ML gate: вероятность ниже порога")
            ));
            return EntryResult.fail("ml_gate_reject");
        }

        Instant effectiveTime = (time != null ? time : Instant.now());

        final String key = entryKey(chatId, strategyType, ex, net, sym);
        if (failCooldown.isBlocked(key)) {
            long leftMs = failCooldown.remainingMs(key);
            if (leftMs > 0 && log.isDebugEnabled()) {
                log.debug("⛔ [Вход] Кулдаун после ошибок | leftMs={} | chatId={} {} {} ex={} net={}",
                        leftMs, chatId, strategyType, sym, ex, net);
            }
            return EntryResult.fail("cooldown");
        }

        boolean restoredBeforeEntry = ensurePositionRecoveredBeforeEntry(
                chatId,
                strategyType,
                sym,
                ex,
                net,
                tpPct,
                slPct,
                effectiveTime
        );

        if (positionStore.isInPosition(chatId, strategyType, ex, net, sym)) {
            if (restoredBeforeEntry) {
                log.warn("♻️ [Вход] Пропуск: позиция восстановлена из истории ордеров | chatId={} {} {} ex={} net={}",
                        chatId, strategyType, sym, ex, net);
                return EntryResult.fail("already_in_position_restored");
            }

            if (log.isDebugEnabled()) {
                log.debug("⛔ [Вход] Уже в позиции | chatId={} {} {} ex={} net={}", chatId, strategyType, sym, ex, net);
            }
            return EntryResult.fail("already_in_position");
        }

        QuoteBudget budget = resolveQuoteBudget(chatId, strategyType, ss, ex, net);
        if (!QtyMath.isPositive(budget.quoteAmount())) {
            log.warn("⛔ [Вход] Нет бюджета для сделки | reason={} | mode={} value={} free={} | chatId={} {} {} ex={} net={}",
                    budget.reason(),
                    budget.mode(),
                    budget.value(),
                    budget.free(),
                    chatId, strategyType, sym, ex, net
            );
            return EntryResult.fail(budget.reason());
        }

        BigDecimal quoteAmount = budget.quoteAmount();
        BigDecimal plannedQty = quoteAmount.divide(price, QTY_SCALE, RoundingMode.DOWN);
        if (plannedQty.signum() <= 0) {
            log.warn("⛔ [Вход] Расчёт qty дал 0 | quoteAmount={} price={} | chatId={} {} {}",
                    QtyMath.strip(quoteAmount), QtyMath.strip(price), chatId, strategyType, sym);
            return EntryResult.fail("qty=0");
        }

        BigDecimal stepSize = tryResolveStepSize(ex, net, sym);
        BigDecimal tradableQty = normalizeTradableQty(plannedQty, stepSize);
        if (!QtyMath.isPositive(tradableQty)) {
            log.warn("⛔ [Вход] После округления по step qty стал 0 | {} plannedQty={} stepSize={} quote={} price={} | chatId={} ex={} net={}",
                    sym,
                    QtyMath.strip(plannedQty),
                    QtyMath.strip(stepSize),
                    QtyMath.strip(quoteAmount),
                    QtyMath.strip(price),
                    chatId,
                    ex,
                    net);
            failCooldown.recordFailure(key, "lot_step", "qty=0 after step rounding");
            return EntryResult.fail("lot_step");
        }

        BigDecimal effectiveNotional = tradableQty.multiply(price).setScale(8, RoundingMode.HALF_UP);
        BigDecimal minNotional = tryResolveMinNotional(ex, net, sym);
        if (!QtyMath.isPositive(minNotional)) {
            minNotional = DEFAULT_MIN_NOTIONAL;
        }

        if (effectiveNotional.compareTo(minNotional) < 0) {
            BigDecimal requiredQty = minNotional.divide(price, QTY_SCALE, RoundingMode.UP);
            BigDecimal requiredQtyRounded = normalizeTradableQty(requiredQty, stepSize);
            String reason = "precheck: qty*price=" + QtyMath.strip(effectiveNotional)
                    + " < minNotional=" + QtyMath.strip(minNotional)
                    + " (нужно qty≥" + QtyMath.strip(requiredQtyRounded) + ")";

            log.warn("⛔ [Вход] PRECHECK MIN_NOTIONAL | {} quote={} plannedQty={} tradableQty={} stepSize={} notional={} minNotional={} needQty={} | chatId={} ex={} net={}",
                    sym,
                    QtyMath.strip(quoteAmount),
                    QtyMath.strip(plannedQty),
                    QtyMath.strip(tradableQty),
                    QtyMath.strip(stepSize),
                    QtyMath.strip(effectiveNotional),
                    QtyMath.strip(minNotional),
                    QtyMath.strip(requiredQtyRounded),
                    chatId,
                    ex,
                    net);

            failCooldown.recordFailure(key, "min_notional", reason);
            return EntryResult.fail("min_notional");
        }

        BigDecimal commissionPct = resolveCommissionPctOrNull(chatId, ex, net, ss);
        if (commissionPct != null && commissionPct.signum() > 0) {
            BigDecimal minTpToNotLose = commissionPct.multiply(BigDecimal.valueOf(2)).add(TP_FEE_BUFFER_PCT);
            if (tpPct.compareTo(minTpToNotLose) < 0) {
                log.warn("⛔ [Вход] TP слишком маленький относительно комиссий | tpPct={} < minTp={} (feePct={} x2 + buffer={}) | chatId={} {} {}",
                        tpPct.stripTrailingZeros().toPlainString(),
                        minTpToNotLose.stripTrailingZeros().toPlainString(),
                        commissionPct.stripTrailingZeros().toPlainString(),
                        TP_FEE_BUFFER_PCT.stripTrailingZeros().toPlainString(),
                        chatId, strategyType, sym
                );
                return EntryResult.fail("tp_too_small_for_fees");
            }
        }

        BigDecimal tp = calcTp(price, tpPct);
        BigDecimal sl = calcSl(price, slPct);

        if (tp.compareTo(price) <= 0) return EntryResult.fail("tp_le_entry");
        if (sl.compareTo(price) >= 0) return EntryResult.fail("sl_ge_entry");
        if (sl.signum() <= 0) return EntryResult.fail("sl_le_0");

        safeLive(() -> live.pushSignal(chatId, strategyType, sym, null, Signal.buy(price.doubleValue(), "entry")));

        if (collectMode) {
            log.info("🧪 [Вход][COLLECT] {} qty={} price={} quote={} tp={} sl={} | mode={} value={} free={} | chatId={} ex={} net={}",
                    sym,
                    tradableQty.stripTrailingZeros().toPlainString(),
                    price.stripTrailingZeros().toPlainString(),
                    quoteAmount.stripTrailingZeros().toPlainString(),
                    tp.stripTrailingZeros().toPlainString(),
                    sl.stripTrailingZeros().toPlainString(),
                    budget.mode(),
                    QtyMath.strip(budget.value()),
                    QtyMath.strip(budget.free()),
                    chatId, ex, net
            );
            return EntryResult.ok(false, "BUY", tradableQty.stripTrailingZeros(), price, tp, sl, null);
        }

        if (paperBlocksNow) {
            positionStore.markOpened(
                    chatId, strategyType, ex, net, sym,
                    price, tradableQty, tp, sl,
                    quoteAmount, null, effectiveTime
            );
            clearRestoreProbeState(restoreProbeKey(chatId, strategyType, ex, net, sym));

            log.info("📄 [Вход][PAPER-СИМ][MAINNET-BLOCK] BUY {} qty={} price={} quote={} tp={} sl={} | chatId={} ex={} net={}",
                    sym,
                    tradableQty.stripTrailingZeros().toPlainString(),
                    price.stripTrailingZeros().toPlainString(),
                    quoteAmount.stripTrailingZeros().toPlainString(),
                    tp.stripTrailingZeros().toPlainString(),
                    sl.stripTrailingZeros().toPlainString(),
                    chatId, ex, net
            );

            return EntryResult.ok(false, "BUY", tradableQty.stripTrailingZeros(), price, tp, sl, null);
        }

        OrderService.OrderContext ctx = new OrderService.OrderContext(
                chatId,
                strategyType,
                sym,
                safe(ss.getTimeframe()),
                null,
                "ENTRY",
                ex,
                net
        );

        try {
            log.info("▶️ [Вход] Отправляю MARKET BUY | {} quote={} price={} qty={} tpPct={} slPct={} phase={} minNotional={} | chatId={} ex={} net={}",
                    sym,
                    quoteAmount.stripTrailingZeros().toPlainString(),
                    price.stripTrailingZeros().toPlainString(),
                    tradableQty.stripTrailingZeros().toPlainString(),
                    tpPct.stripTrailingZeros().toPlainString(),
                    slPct.stripTrailingZeros().toPlainString(),
                    phase,
                    QtyMath.strip(minNotional),
                    chatId, ex, net
            );

            Order order = orderService.placeMarket(ctx, OrderSide.BUY, quoteAmount, price);
            Long orderId = (order != null ? order.getId() : null);

            BigDecimal executedQty = pickExecutedQty(order, tradableQty);
            BigDecimal executedPrice = pickExecutedPrice(order, price);
            BigDecimal runtimeQty = normalizeRuntimeQty(executedQty, ex, net, sym);

            BigDecimal tpExec = calcTp(executedPrice, tpPct);
            BigDecimal slExec = calcSl(executedPrice, slPct);

            persistEntryRisk(orderId, ex, net, tpExec, slExec);

            failCooldown.clear(key);
            failCooldown.clear(key + EXIT_KEY_SUFFIX);

            positionStore.markOpened(
                    chatId,
                    strategyType,
                    ex,
                    net,
                    sym,
                    executedPrice,
                    runtimeQty,
                    tpExec,
                    slExec,
                    quoteAmount,
                    orderId,
                    effectiveTime
            );
            clearRestoreProbeState(restoreProbeKey(chatId, strategyType, ex, net, sym));

            log.info("✅ [Вход] BUY исполнен | {} qty={} entryPrice={} tp={} sl={} orderId={} | chatId={} ex={} net={}",
                    sym,
                    runtimeQty.stripTrailingZeros().toPlainString(),
                    executedPrice.stripTrailingZeros().toPlainString(),
                    tpExec.stripTrailingZeros().toPlainString(),
                    slExec.stripTrailingZeros().toPlainString(),
                    orderId,
                    chatId, ex, net
            );

            return EntryResult.ok(true, "BUY", runtimeQty.stripTrailingZeros(), executedPrice, tpExec, slExec, orderId);

        } catch (Exception e) {
            String code = mapTradeErrorCode(e);
            failCooldown.recordFailure(key, code, e.getMessage());

            log.warn("💥 [Вход] BUY не удался | {} | code={} | chatId={} ex={} net={} | err={}",
                    sym, code, chatId, ex, net, e.toString());
            log.debug("💥 [Вход] stack", e);

            return EntryResult.fail(code);
        }
    }

    // =====================================================
    // EXIT
    // =====================================================

    @Override
    public ExitResult executeExitIfHit(Long chatId,
                                       StrategyType strategyType,
                                       String symbol,
                                       BigDecimal price,
                                       Instant time,
                                       boolean isLong,
                                       BigDecimal entryQty,
                                       BigDecimal tp,
                                       BigDecimal sl,
                                       String exchange,
                                       NetworkType network) {

        if (chatId == null) return ExitResult.fail("chatId=null");
        if (strategyType == null) return ExitResult.fail("strategyType=null");

        StrategySettings ss = tryLoadSettings(chatId, strategyType);

        String ex = safeExchange(exchange);
        NetworkType net = network;

        if (ss != null) {
            if (ex == null) ex = safeExchange(ss.getExchangeName());
            if (net == null) net = ss.getNetworkType();
        }

        if (ex == null) return ExitResult.fail("exchange=null");
        if (net == null) return ExitResult.fail("network=null");

        return doExitIfHitInternal(chatId, strategyType, symbol, price, time, isLong, entryQty, tp, sl, ss, ex, net);
    }

    public ExitResult executeExitIfHit(Long chatId,
                                       StrategyType strategyType,
                                       String symbol,
                                       BigDecimal price,
                                       Instant time,
                                       boolean isLong,
                                       BigDecimal entryQty,
                                       BigDecimal tp,
                                       BigDecimal sl) {
        StrategySettings ss = tryLoadSettings(chatId, strategyType);
        String ex = ss != null ? safeExchange(ss.getExchangeName()) : null;
        NetworkType net = ss != null ? ss.getNetworkType() : null;
        return executeExitIfHit(chatId, strategyType, symbol, price, time, isLong, entryQty, tp, sl, ex, net);
    }

    private ExitResult doExitIfHitInternal(Long chatId,
                                           StrategyType strategyType,
                                           String symbol,
                                           BigDecimal price,
                                           Instant time,
                                           boolean isLong,
                                           BigDecimal entryQty,
                                           BigDecimal tp,
                                           BigDecimal sl,
                                           StrategySettings ss,
                                           String exchange,
                                           NetworkType network) {

        String sym = normalizeSymbol(symbol);
        if (sym == null) return ExitResult.fail("symbol пустой");
        if (price == null || price.signum() <= 0) return ExitResult.fail("price_invalid");
        if (!isLong) return ExitResult.fail("spot_short_forbidden");

        String ex = safeExchange(exchange);
        NetworkType net = network;
        if (ex == null) return ExitResult.fail("exchange=null");
        if (net == null) return ExitResult.fail("network=null");

        Instant effectiveTime = (time != null ? time : Instant.now());

        String phase = (ss != null ? normalizeUpperNullable(ss.getRunPhase()) : null);
        boolean collectMode = PHASE_COLLECT.equals(phase);
        boolean paperMode   = PHASE_PAPER.equals(phase);

        if (PHASE_BACKTEST.equals(phase)) return ExitResult.fail("runPhase=BACKTEST");
        if (collectMode) {
            try { positionStore.clearPosition(chatId, strategyType, ex, net, sym); } catch (Exception ignored) {}
            return ExitResult.fail("collect_mode");
        }

        boolean paperBlocksNow = paperMode && paperBlocksRealOrders && net == NetworkType.MAINNET;

        final String exitKey = entryKey(chatId, strategyType, ex, net, sym) + EXIT_KEY_SUFFIX;
        if (failCooldown.isBlocked(exitKey)) {
            long leftMs = failCooldown.remainingMs(exitKey);
            if (leftMs > 0) {
                log.debug("⛔ [Выход] Кулдаун после ошибок | leftMs={} | chatId={} {} {} ex={} net={}",
                        leftMs, chatId, strategyType, sym, ex, net);
            }
            return ExitResult.fail("cooldown");
        }

        BigDecimal effQty = entryQty;
        BigDecimal effTp  = tp;
        BigDecimal effSl  = sl;

        BigDecimal entryPriceFromStore = null;

        Optional<PositionStore.PositionSnapshot> posOpt = Optional.empty();
        try {
            posOpt = positionStore.getPosition(chatId, strategyType, ex, net, sym);
            if (posOpt.isPresent()) {
                PositionStore.PositionSnapshot snap = posOpt.get();
                if (snap.qty() != null && snap.qty().signum() > 0) effQty = snap.qty();
                if (snap.tp()  != null && snap.tp().signum()  > 0) effTp  = snap.tp();
                if (snap.sl()  != null && snap.sl().signum()  > 0) effSl  = snap.sl();
                if (snap.entryPrice() != null && snap.entryPrice().signum() > 0) entryPriceFromStore = snap.entryPrice();
            }
        } catch (Exception ignored) {}

        if (posOpt.isEmpty() && !isRestoreSuppressedInStore(chatId, strategyType, ex, net, sym)) {
            Optional<RecoveredPosition> recovered = recoverOpenPositionFromOrders(chatId, strategyType, sym, ex, net, effectiveTime);
            if (recovered.isPresent()) {
                Optional<RecoveredPosition> alignedRecovered = alignRecoveredPositionToExchangeBalance(
                        chatId,
                        strategyType,
                        ex,
                        net,
                        sym,
                        recovered.get()
                );

                if (alignedRecovered.isPresent()) {
                    RecoveredPosition rp = alignedRecovered.get();

                    BigDecimal restoredTp = positiveOrNull(effTp);
                    BigDecimal restoredSl = positiveOrNull(effSl);

                    if (restoredTp == null || restoredSl == null) {
                        restoredTp = positiveOrNull(rp.tp());
                        restoredSl = positiveOrNull(rp.sl());
                    }

                    if (restoredTp == null || restoredSl == null) {
                        log.warn("⚠️ [Выход] Позиция найдена в истории, но TP/SL не удалось восстановить | chatId={} {} {} ex={} net={}",
                                chatId, strategyType, sym, ex, net);
                        return ExitResult.fail("tp/sl_null");
                    }

                    positionStore.markOpened(
                            chatId,
                            strategyType,
                            ex,
                            net,
                            sym,
                            rp.entryPrice(),
                            rp.qty(),
                            restoredTp,
                            restoredSl,
                            rp.quoteSpent(),
                            rp.entryOrderId(),
                            rp.openedAt()
                    );

                    posOpt = positionStore.getPosition(chatId, strategyType, ex, net, sym);
                    if (posOpt.isPresent()) {
                        PositionStore.PositionSnapshot snap = posOpt.get();
                        if (snap.qty() != null && snap.qty().signum() > 0) effQty = snap.qty();
                        if (snap.tp()  != null && snap.tp().signum()  > 0) effTp  = snap.tp();
                        if (snap.sl()  != null && snap.sl().signum()  > 0) effSl  = snap.sl();
                        if (snap.entryPrice() != null && snap.entryPrice().signum() > 0) entryPriceFromStore = snap.entryPrice();

                        log.warn("♻️ [Выход] Позиция восстановлена из истории перед SELL | chatId={} {} {} ex={} net={} qty={} entry={}",
                                chatId, strategyType, sym, ex, net,
                                QtyMath.strip(snap.qty()),
                                QtyMath.strip(snap.entryPrice()));
                    }
                }
            }
        }

        if (effQty == null || effQty.signum() <= 0) return ExitResult.fail("entryQty_invalid");
        if (effTp == null || effSl == null) return ExitResult.fail("tp/sl_null");

        boolean tpHit = price.compareTo(effTp) >= 0;
        boolean slHit = price.compareTo(effSl) <= 0;
        if (!tpHit && !slHit) return ExitResult.fail("not_hit");

        if (posOpt.isEmpty()) {
            failCooldown.recordFailure(exitKey, "no_real_position", "no snapshot in PositionStore");
            log.warn("⛔ [Выход] Пропуск: нет позиции в PositionStore и не удалось восстановить из истории | {} | chatId={} ex={} net={} tpHit={} slHit={}",
                    sym, chatId, ex, net, tpHit, slHit);
            try { positionStore.clearPosition(chatId, strategyType, ex, net, sym); } catch (Exception ignored) {}
            return ExitResult.fail("no_real_position");
        }

        if (paperBlocksNow) {
            BigDecimal executedExitPrice = price;
            BigDecimal pnlPct = calcPnlPct(entryPriceFromStore, executedExitPrice);

            safeLive(() -> live.clearTpSl(chatId, strategyType, sym));
            safeLive(() -> live.clearPriceLines(chatId, strategyType, sym));
            safeLive(() -> live.pushSignal(chatId, strategyType, sym, null,
                    Signal.sell(executedExitPrice.doubleValue(), tpHit ? "TP" : "SL")));

            log.info("📄 [Выход][PAPER-СИМ][MAINNET-BLOCK] SELL {} qty={} exitPrice={} reason={} pnlPct={} | chatId={} ex={} net={}",
                    sym,
                    effQty.stripTrailingZeros().toPlainString(),
                    executedExitPrice.stripTrailingZeros().toPlainString(),
                    tpHit ? "TP" : "SL",
                    pnlPct != null ? pnlPct.stripTrailingZeros().toPlainString() : "null",
                    chatId, ex, net
            );

            publishTradeClosedEvent(
                    chatId, strategyType, sym,
                    (ss != null ? ss.getTimeframe() : null),
                    ex, net,
                    effectiveTime,
                    tpHit ? "TP" : "SL",
                    pnlPct,
                    executedExitPrice,
                    entryPriceFromStore,
                    effQty,
                    effTp,
                    effSl,
                    tpHit,
                    slHit
            );

            try { positionStore.clearPosition(chatId, strategyType, ex, net, sym); } catch (Exception ignored) {}
            failCooldown.clear(exitKey);

            return ExitResult.ok(tpHit, slHit, executedExitPrice, pnlPct != null ? pnlPct : BigDecimal.ZERO);
        }

        OrderService.OrderContext ctx = new OrderService.OrderContext(
                chatId,
                strategyType,
                sym,
                (ss != null ? safe(ss.getTimeframe()) : null),
                null,
                "EXIT",
                ex,
                net
        );

        BigDecimal freeBase = resolveFreeBaseQty(chatId, strategyType, ss, ex, net, sym);

        if (freeBase != null) {
            if (QtyMath.isPositive(freeBase)) {
                if (freeBase.compareTo(effQty) < 0) {
                    log.warn("⚠️ [Выход] Корректирую qty по свободному base балансу | {} baseFree={} plannedQty={} tpHit={} slHit={} | chatId={} ex={} net={}",
                            sym, QtyMath.strip(freeBase), QtyMath.strip(effQty), tpHit, slHit, chatId, ex, net);
                    effQty = freeBase;
                }
            } else {
                failCooldown.recordFailure(exitKey, "balance", "base_free=0 for " + sym);
                log.error("⛔ [Выход] Нет свободного base баланса | {} qty={} tpHit={} slHit={} | chatId={} ex={} net={}",
                        sym, QtyMath.strip(effQty), tpHit, slHit, chatId, ex, net);
                try { positionStore.clearPosition(chatId, strategyType, ex, net, sym); } catch (Exception ignored) {}
                return ExitResult.fail("balance");
            }
        }

        BigDecimal stepSize = tryResolveStepSize(ex, net, sym);
        if (QtyMath.isPositive(stepSize)) {
            BigDecimal floored = QtyMath.floorToStepOrZero(effQty, stepSize);

            if (!QtyMath.isPositive(floored)) {
                String msg = "qty меньше stepSize (" + QtyMath.strip(stepSize) + ")";
                failCooldown.recordFailure(exitKey, "lot_step", msg);
                log.warn("⛔ [Выход] DUST: qty слишком маленький | {} qty={} stepSize={} tpHit={} slHit={} | chatId={} ex={} net={}",
                        sym, QtyMath.strip(effQty), QtyMath.strip(stepSize), tpHit, slHit, chatId, ex, net);
                return ExitResult.fail("lot_step");
            }

            effQty = floored;
        }

        if (effQty.signum() <= 0) {
            failCooldown.recordFailure(exitKey, "balance", "effQty<=0 after adjust");
            return ExitResult.fail("balance");
        }

        try {
            log.info("▶️ [Выход] Отправляю MARKET SELL | {} qty={} tickPrice={} reason={} | chatId={} ex={} net={}",
                    sym,
                    effQty.stripTrailingZeros().toPlainString(),
                    price.stripTrailingZeros().toPlainString(),
                    tpHit ? "TP" : "SL",
                    chatId, ex, net
            );

            Order order = orderService.placeMarket(ctx, OrderSide.SELL, effQty, price);
            BigDecimal executedExitPrice = pickExecutedPrice(order, price);

            safeLive(() -> live.clearTpSl(chatId, strategyType, sym));
            safeLive(() -> live.clearPriceLines(chatId, strategyType, sym));
            safeLive(() -> live.pushSignal(chatId, strategyType, sym, null,
                    Signal.sell(executedExitPrice.doubleValue(), tpHit ? "TP" : "SL")));

            BigDecimal pnlPct = calcPnlPct(entryPriceFromStore, executedExitPrice);

            log.info("✅ [Выход] SELL исполнен | {} qty={} exitPrice={} pnlPct={} tpHit={} slHit={} | chatId={} ex={} net={}",
                    sym,
                    effQty.stripTrailingZeros().toPlainString(),
                    executedExitPrice.stripTrailingZeros().toPlainString(),
                    pnlPct != null ? pnlPct.stripTrailingZeros().toPlainString() : "null",
                    tpHit,
                    slHit,
                    chatId,
                    ex,
                    net
            );

            publishTradeClosedEvent(
                    chatId,
                    strategyType,
                    sym,
                    (ss != null ? ss.getTimeframe() : null),
                    ex,
                    net,
                    effectiveTime,
                    tpHit ? "TP" : "SL",
                    pnlPct,
                    executedExitPrice,
                    entryPriceFromStore,
                    effQty,
                    effTp,
                    effSl,
                    tpHit,
                    slHit
            );

            try { positionStore.clearPosition(chatId, strategyType, ex, net, sym); } catch (Exception ignored) {}
            failCooldown.clear(exitKey);

            boolean isLoss = (pnlPct != null && pnlPct.signum() < 0);

            if ((slHit || isLoss) && allowAutoTune(ss)) {
                String reason = slHit ? "after-close:sl" : "after-close:loss";
                if (pnlPct != null) reason += ":pnlPct=" + pnlPct.stripTrailingZeros().toPlainString();

                mlAutoTuneRuntime.triggerTuneDebounced(
                        chatId,
                        strategyType,
                        ex,
                        net,
                        reason,
                        Duration.ofSeconds(5)
                );
            }

            return ExitResult.ok(tpHit, slHit, executedExitPrice, pnlPct != null ? pnlPct : BigDecimal.ZERO);

        } catch (Exception e) {
            String code = mapTradeErrorCode(e);

            if ("lot_step".equals(code) || "min_notional".equals(code)) {
                failCooldown.recordFailure(exitKey, code, e.getMessage());
                clearDustPositionFromRuntime(chatId, strategyType, ex, net, sym, effQty, price, code, e.getMessage());
                return ExitResult.fail("dust_position");
            }

            if ("balance".equals(code)) {
                failCooldown.recordFailure(exitKey, code, e.getMessage());

                BigDecimal retryQty = null;
                if (QtyMath.isPositive(stepSize)) {
                    retryQty = QtyMath.floorToStepOrZero(effQty.subtract(stepSize), stepSize);
                }

                if (QtyMath.isPositive(retryQty) && retryQty.compareTo(effQty) < 0) {
                    try {
                        log.warn("♻️ [Выход] Повторяю SELL с уменьшенным qty после balance error | {} oldQty={} retryQty={} step={} | chatId={} ex={} net={}",
                                sym,
                                QtyMath.strip(effQty),
                                QtyMath.strip(retryQty),
                                QtyMath.strip(stepSize),
                                chatId,
                                ex,
                                net);

                        Order retryOrder = orderService.placeMarket(ctx, OrderSide.SELL, retryQty, price);
                        BigDecimal executedExitPrice = pickExecutedPrice(retryOrder, price);
                        BigDecimal pnlPct = calcPnlPct(entryPriceFromStore, executedExitPrice);

                        safeLive(() -> live.clearTpSl(chatId, strategyType, sym));
                        safeLive(() -> live.clearPriceLines(chatId, strategyType, sym));
                        safeLive(() -> live.pushSignal(chatId, strategyType, sym, null,
                                Signal.sell(executedExitPrice.doubleValue(), tpHit ? "TP" : "SL")));

                        publishTradeClosedEvent(
                                chatId,
                                strategyType,
                                sym,
                                (ss != null ? ss.getTimeframe() : null),
                                ex,
                                net,
                                effectiveTime,
                                tpHit ? "TP" : "SL",
                                pnlPct,
                                executedExitPrice,
                                entryPriceFromStore,
                                retryQty,
                                effTp,
                                effSl,
                                tpHit,
                                slHit
                        );

                        try { positionStore.clearPosition(chatId, strategyType, ex, net, sym); } catch (Exception ignored) {}
                        failCooldown.clear(exitKey);

                        log.info("✅ [Выход] SELL исполнен после qty-retry | {} qty={} exitPrice={} pnlPct={} | chatId={} ex={} net={}",
                                sym,
                                QtyMath.strip(retryQty),
                                QtyMath.strip(executedExitPrice),
                                (pnlPct != null ? QtyMath.strip(pnlPct) : "null"),
                                chatId,
                                ex,
                                net);

                        return ExitResult.ok(tpHit, slHit, executedExitPrice, pnlPct != null ? pnlPct : BigDecimal.ZERO);

                    } catch (Exception retryEx) {
                        log.warn("⚠️ [Выход] Повторный SELL после balance error тоже не удался chatId={} sym={} retryQty={} err={}",
                                chatId,
                                sym,
                                QtyMath.strip(retryQty),
                                retryEx.toString());
                    }
                }

                clearDustPositionFromRuntime(chatId, strategyType, ex, net, sym, effQty, price, code, e.getMessage());
                return ExitResult.fail("dust_position");
            }

            log.error("💥 [Выход] SELL не удался | {} | code={} | chatId={} ex={} net={} | err={}",
                    sym, code, chatId, ex, net, e.toString(), e);
            return ExitResult.fail(code);
        }
    }

    // =====================================================
    // events / budget / ml / helpers
    // =====================================================

    private void publishTradeClosedEvent(Long chatId,
                                         StrategyType strategyType,
                                         String symbol,
                                         String timeframe,
                                         String exchange,
                                         NetworkType network,
                                         Instant closedAt,
                                         String exitReason,
                                         BigDecimal pnlPct,
                                         BigDecimal exitPrice,
                                         BigDecimal entryPrice,
                                         BigDecimal qty,
                                         BigDecimal tpPrice,
                                         BigDecimal slPrice,
                                         Boolean tpHit,
                                         Boolean slHit) {
        if (eventPublisher == null) return;
        try {
            eventPublisher.publishEvent(new TradeClosedEvent(
                    chatId,
                    strategyType,
                    symbol,
                    timeframe,
                    exchange,
                    network,
                    closedAt,
                    exitReason,
                    pnlPct,
                    exitPrice,
                    entryPrice,
                    qty,
                    tpPrice,
                    slPrice,
                    tpHit,
                    slHit
            ));
        } catch (Exception e) {
            log.warn("⚠️ [Трейд] Не удалось отправить TradeClosedEvent chatId={} type={} sym={} err={}",
                    chatId, strategyType, symbol, e.toString());
        }
    }

    private record QuoteBudget(BigDecimal quoteAmount,
                               StrategySettings.CapitalMode mode,
                               BigDecimal value,
                               BigDecimal free,
                               String reason) {}

    private record MlGateDecision(boolean reject,
                                  boolean bypassed,
                                  BigDecimal confidence,
                                  BigDecimal minProb,
                                  String reason) {
        static MlGateDecision pass(BigDecimal confidence, BigDecimal minProb) {
            return new MlGateDecision(false, false, confidence, minProb, "pass");
        }

        static MlGateDecision bypass(BigDecimal confidence, BigDecimal minProb, String reason) {
            return new MlGateDecision(false, true, confidence, minProb, reason);
        }

        static MlGateDecision reject(BigDecimal confidence, BigDecimal minProb, String reason) {
            return new MlGateDecision(true, false, confidence, minProb, reason);
        }
    }

    private record RecoveredPosition(BigDecimal entryPrice,
                                     BigDecimal qty,
                                     BigDecimal quoteSpent,
                                     Long entryOrderId,
                                     Instant openedAt,
                                     BigDecimal tp,
                                     BigDecimal sl) {}

    private static final class OpenLot {
        private BigDecimal qty;
        private final BigDecimal price;
        private final BigDecimal tp;
        private final BigDecimal sl;
        private final Long orderId;
        private final Instant openedAt;

        private OpenLot(BigDecimal qty,
                        BigDecimal price,
                        BigDecimal tp,
                        BigDecimal sl,
                        Long orderId,
                        Instant openedAt) {
            this.qty = qty;
            this.price = price;
            this.tp = tp;
            this.sl = sl;
            this.orderId = orderId;
            this.openedAt = openedAt;
        }
    }

    private QuoteBudget resolveQuoteBudget(Long chatId,
                                           StrategyType strategyType,
                                           StrategySettings ss,
                                           String ex,
                                           NetworkType net) {

        if (chatId == null || strategyType == null || ss == null) {
            return new QuoteBudget(BigDecimal.ZERO, null, null, null, "bad_args");
        }
        if (accountBalanceService == null) {
            return new QuoteBudget(BigDecimal.ZERO, ss.getCapitalMode(), ss.getCapitalValue(), null, "no_balance_service");
        }

        AccountBalanceSnapshot snap = accountBalanceService.getSnapshot(chatId, strategyType, ex, net);
        if (snap == null) {
            return new QuoteBudget(BigDecimal.ZERO, ss.getCapitalMode(), ss.getCapitalValue(), null, "no_exchange_balance");
        }
        if (!snap.isConnectionOk()) {
            return new QuoteBudget(BigDecimal.ZERO, ss.getCapitalMode(), ss.getCapitalValue(), null, "exchange_connection_bad");
        }

        AccountBalanceSnapshot.AssetBalance bal = snap.getSelectedBalance();
        BigDecimal free = (bal != null) ? bal.getFree() : null;
        if (!QtyMath.isPositive(free)) {
            return new QuoteBudget(BigDecimal.ZERO, ss.getCapitalMode(), ss.getCapitalValue(), free, "no_free_balance");
        }

        StrategySettings.CapitalMode mode = ss.getCapitalMode();
        if (mode == null) mode = StrategySettings.CapitalMode.ALL;

        BigDecimal value = ss.getCapitalValue();

        BigDecimal quote;
        switch (mode) {
            case ALL -> quote = free;
            case FIX -> {
                if (!QtyMath.isPositive(value)) return new QuoteBudget(BigDecimal.ZERO, mode, value, free, "capital_invalid");
                quote = free.min(value);
            }
            case PCT -> {
                if (!QtyMath.isPositive(value)) return new QuoteBudget(BigDecimal.ZERO, mode, value, free, "capital_invalid");
                BigDecimal pct = value;
                if (pct.compareTo(BigDecimal.valueOf(100)) > 0) pct = BigDecimal.valueOf(100);
                quote = free.multiply(pct).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
            }
            default -> {
                return new QuoteBudget(BigDecimal.ZERO, mode, value, free, "capital_mode_invalid");
            }
        }

        if (!QtyMath.isPositive(quote)) {
            return new QuoteBudget(BigDecimal.ZERO, mode, value, free, "no_budget");
        }

        return new QuoteBudget(quote.stripTrailingZeros(), mode, value, free, "ok");
    }

    private boolean ensurePositionRecoveredBeforeEntry(Long chatId,
                                                       StrategyType strategyType,
                                                       String symbol,
                                                       String exchange,
                                                       NetworkType network,
                                                       BigDecimal tpPct,
                                                       BigDecimal slPct,
                                                       Instant now) {

        if (chatId == null || strategyType == null || symbol == null || exchange == null || network == null) {
            return false;
        }

        Instant probeNow = (now != null ? now : Instant.now());
        String restoreKey = restoreProbeKey(chatId, strategyType, exchange, network, symbol);

        if (positionStore.isInPosition(chatId, strategyType, exchange, network, symbol)) {
            clearRestoreProbeState(restoreKey);
            return false;
        }

        if (isRestoreSuppressedInStore(chatId, strategyType, exchange, network, symbol)) {
            rememberRestoreMiss(restoreKey, probeNow);
            return false;
        }

        if (isRestoreProbeBlocked(restoreKey, probeNow)) {
            return false;
        }

        Optional<RecoveredPosition> recovered = recoverOpenPositionFromOrders(chatId, strategyType, symbol, exchange, network, probeNow);
        if (recovered.isEmpty()) {
            rememberRestoreMiss(restoreKey, probeNow);
            return false;
        }

        Optional<RecoveredPosition> alignedRecovered = alignRecoveredPositionToExchangeBalance(
                chatId,
                strategyType,
                exchange,
                network,
                symbol,
                recovered.get()
        );

        if (alignedRecovered.isEmpty()) {
            rememberRestoreMiss(restoreKey, probeNow);
            return false;
        }

        RecoveredPosition rp = alignedRecovered.get();

        BigDecimal tpPrice = positiveOrNull(rp.tp());
        BigDecimal slPrice = positiveOrNull(rp.sl());

        if (tpPrice == null && isValidPct(tpPct)) {
            tpPrice = calcTp(rp.entryPrice(), tpPct);
        }
        if (slPrice == null && isValidPct(slPct)) {
            slPrice = calcSl(rp.entryPrice(), slPct);
        }

        if (tpPrice == null || slPrice == null) {
            rememberRestoreMiss(restoreKey, probeNow);
            log.warn("⚠️ [Восстановление] Нашёл открытую позицию, но не удалось собрать TP/SL | chatId={} {} {}",
                    chatId, strategyType, symbol);
            return false;
        }

        positionStore.markOpened(
                chatId,
                strategyType,
                exchange,
                network,
                symbol,
                rp.entryPrice(),
                rp.qty(),
                tpPrice,
                slPrice,
                rp.quoteSpent(),
                rp.entryOrderId(),
                rp.openedAt()
        );

        clearRestoreProbeState(restoreKey);

        log.warn("♻️ [Восстановление] Найдена открытая позиция из истории ордеров | chatId={} {} {} ex={} net={} qty={} entry={} tp={} sl={} orderId={}",
                chatId,
                strategyType,
                symbol,
                exchange,
                network,
                QtyMath.strip(rp.qty()),
                QtyMath.strip(rp.entryPrice()),
                QtyMath.strip(tpPrice),
                QtyMath.strip(slPrice),
                rp.entryOrderId());

        return true;
    }

    private Optional<RecoveredPosition> recoverOpenPositionFromOrders(Long chatId,
                                                                      StrategyType strategyType,
                                                                      String symbol,
                                                                      String exchange,
                                                                      NetworkType network,
                                                                      Instant fallbackNow) {
        if (chatId == null || strategyType == null || symbol == null) {
            return Optional.empty();
        }
        if (orderRepository == null) {
            return Optional.empty();
        }

        List<OrderEntity> orders;
        try {
            orders = orderRepository.findByChatIdAndSymbolOrderByTimestampAsc(chatId, symbol);
        } catch (Exception e) {
            log.warn("⚠️ [Восстановление] Не удалось прочитать orders | chatId={} {} {} err={}",
                    chatId, strategyType, symbol, e.toString());
            return Optional.empty();
        }

        if (orders == null || orders.isEmpty()) {
            return Optional.empty();
        }

        List<OrderEntity> scoped = filterOrdersByContext(orders, strategyType, exchange, network);
        if (scoped.isEmpty()) {
            scoped = filterOrdersLegacy(orders, strategyType);
        }
        if (scoped.isEmpty()) {
            return Optional.empty();
        }

        List<OpenLot> openLots = new ArrayList<>();

        for (OrderEntity order : scoped) {
            if (order == null) continue;
            if (!Boolean.TRUE.equals(order.getFilled())) continue;

            String side = normalizeUpperNullable(order.getSide());
            BigDecimal qty = positiveOrNull(order.getQuantity());
            BigDecimal px  = positiveOrNull(order.getPrice());

            if (qty == null || px == null) continue;

            if ("BUY".equals(side)) {
                openLots.add(new OpenLot(
                        qty,
                        px,
                        positiveOrNull(order.getTakeProfitPrice()),
                        positiveOrNull(order.getStopLossPrice()),
                        order.getId(),
                        resolveOrderInstant(order, fallbackNow)
                ));
                continue;
            }

            if ("SELL".equals(side)) {
                BigDecimal leftToClose = qty;

                while (leftToClose.signum() > 0 && !openLots.isEmpty()) {
                    OpenLot first = openLots.get(0);

                    if (first.qty.compareTo(leftToClose) <= 0) {
                        leftToClose = leftToClose.subtract(first.qty);
                        openLots.remove(0);
                    } else {
                        first.qty = first.qty.subtract(leftToClose);
                        leftToClose = BigDecimal.ZERO;
                    }
                }
            }
        }

        if (openLots.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        Long lastOrderId = null;
        Instant openedAt = null;

        BigDecimal restoredTp = null;
        BigDecimal restoredSl = null;

        for (OpenLot lot : openLots) {
            if (lot == null || lot.qty == null || lot.price == null) continue;
            if (lot.qty.signum() <= 0 || lot.price.signum() <= 0) continue;

            totalQty = totalQty.add(lot.qty);
            totalCost = totalCost.add(lot.qty.multiply(lot.price));

            if (openedAt == null || (lot.openedAt != null && lot.openedAt.isBefore(openedAt))) {
                openedAt = lot.openedAt;
            }
            lastOrderId = lot.orderId;

            if (restoredTp == null && lot.tp != null && lot.tp.signum() > 0) {
                restoredTp = lot.tp;
            }
            if (restoredSl == null && lot.sl != null && lot.sl.signum() > 0) {
                restoredSl = lot.sl;
            }
        }

        if (totalQty.signum() <= 0 || totalCost.signum() <= 0) {
            return Optional.empty();
        }

        if (totalCost.compareTo(MIN_RESTORABLE_NOTIONAL) < 0) {
            String restoreKey = restoreProbeKey(chatId, strategyType, exchange, network, symbol);
            if (shouldLogDustRestoreNow(restoreKey, fallbackNow != null ? fallbackNow : Instant.now())) {
                log.warn("🧹 [Восстановление] Пропускаю dust-позицию chatId={} type={} sym={} ex={} net={} qty={} notional={} minRestorable={}",
                        chatId,
                        strategyType,
                        symbol,
                        exchange,
                        network,
                        QtyMath.strip(totalQty),
                        QtyMath.strip(totalCost),
                        QtyMath.strip(MIN_RESTORABLE_NOTIONAL));
            }
            return Optional.empty();
        }

        BigDecimal avgEntry = totalCost.divide(totalQty, 12, RoundingMode.HALF_UP);

        return Optional.of(new RecoveredPosition(
                avgEntry,
                totalQty.stripTrailingZeros(),
                totalCost.stripTrailingZeros(),
                lastOrderId,
                (openedAt != null ? openedAt : fallbackNow),
                restoredTp,
                restoredSl
        ));
    }

    private List<OrderEntity> filterOrdersByContext(List<OrderEntity> orders,
                                                    StrategyType strategyType,
                                                    String exchange,
                                                    NetworkType network) {
        List<OrderEntity> out = new ArrayList<>();
        String st = strategyType.name();
        String ex = safeExchange(exchange);
        String net = (network != null ? network.name() : null);

        for (OrderEntity o : orders) {
            if (o == null) continue;
            if (!st.equalsIgnoreCase(safe(o.getStrategyType()))) continue;

            String rowEx = safeExchange(o.getExchangeName());
            String rowNet = normalizeUpperNullable(o.getNetworkType());

            if (rowEx == null || rowNet == null) continue;
            if (!rowEx.equals(ex)) continue;
            if (!rowNet.equals(net)) continue;

            out.add(o);
        }
        return out;
    }

    private List<OrderEntity> filterOrdersLegacy(List<OrderEntity> orders,
                                                 StrategyType strategyType) {
        List<OrderEntity> out = new ArrayList<>();
        String st = strategyType.name();

        for (OrderEntity o : orders) {
            if (o == null) continue;
            if (!st.equalsIgnoreCase(safe(o.getStrategyType()))) continue;
            out.add(o);
        }
        return out;
    }

    private Instant resolveOrderInstant(OrderEntity order, Instant fallback) {
        if (order == null) return fallback;

        try {
            if (order.getTimestamp() != null && order.getTimestamp() > 0) {
                return Instant.ofEpochMilli(order.getTimestamp());
            }
        } catch (Exception ignored) {}

        try {
            if (order.getCreatedAt() != null) {
                return order.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant();
            }
        } catch (Exception ignored) {}

        return fallback;
    }

    private MlGateDecision evaluateMlGate(StrategySettings ss) {
        if (ss == null) {
            return MlGateDecision.pass(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        BigDecimal confidenceRaw = ss.getMlConfidence();
        BigDecimal confidence = normalizeProb(confidenceRaw);

        if (!ss.isMlGateEnabled()) {
            return MlGateDecision.pass(confidence, BigDecimal.ZERO);
        }

        AdvancedControlMode mode = ss.getAdvancedControlMode() != null
                ? ss.getAdvancedControlMode()
                : AdvancedControlMode.MANUAL;

        if (mode == AdvancedControlMode.MANUAL) {
            return MlGateDecision.pass(confidence, BigDecimal.ZERO);
        }

        BigDecimal minProb = normalizeProb(ss.getGateMinProb());
        if (minProb.signum() <= 0) {
            return MlGateDecision.pass(confidence, minProb);
        }

        boolean mlConfidenceMissing = (confidenceRaw == null);

        if (mlConfidenceMissing && mode == AdvancedControlMode.HYBRID) {
            return MlGateDecision.bypass(
                    BigDecimal.ZERO,
                    minProb,
                    "hybrid_fail_open_no_ml_confidence"
            );
        }

        if (mlConfidenceMissing && mode == AdvancedControlMode.AI) {
            return MlGateDecision.reject(
                    BigDecimal.ZERO,
                    minProb,
                    "ai_mode_requires_ml_confidence"
            );
        }

        if (confidence.compareTo(minProb) < 0) {
            return MlGateDecision.reject(confidence, minProb, "confidence_below_threshold");
        }

        return MlGateDecision.pass(confidence, minProb);
    }

    private BigDecimal normalizeProb(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO;
        if (value.signum() < 0) return BigDecimal.ZERO;
        if (value.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE;
        return value;
    }

    private String buildMlGateLogKey(Long chatId,
                                     StrategyType strategyType,
                                     String exchange,
                                     NetworkType network,
                                     String symbol) {
        return "ml_gate:" + entryKey(chatId, strategyType, exchange, network, symbol);
    }

    private boolean shouldLogNow(String key, long throttleMs) {
        if (key == null || key.isBlank()) return true;

        Instant now = Instant.now();
        Instant prev = throttledLogTimes.get(key);

        if (prev != null) {
            long age = Duration.between(prev, now).toMillis();
            if (age >= 0 && age < throttleMs) {
                return false;
            }
        }

        throttledLogTimes.put(key, now);
        return true;
    }

    private void safeLive(Runnable r) {
        try { r.run(); } catch (Exception ignored) {}
    }

    private boolean allowAutoTune(StrategySettings s) {
        if (s == null) return false;

        AdvancedControlMode mode = s.getAdvancedControlMode() != null
                ? s.getAdvancedControlMode()
                : AdvancedControlMode.MANUAL;

        if (mode == AdvancedControlMode.MANUAL) return false;

        String phase = normalizeUpperNullable(s.getRunPhase());
        boolean phaseBlocks = PHASE_BACKTEST.equals(phase) || PHASE_COLLECT.equals(phase);
        if (phaseBlocks) return false;

        return s.isAutoTuneEnabled();
    }

    private StrategySettings tryLoadSettings(Long chatId, StrategyType type) {
        if (settingsService == null || chatId == null || type == null) return null;
        try {
            return settingsService.getOrCreate(chatId, type);
        } catch (Exception ignored) {
            return null;
        }
    }

    

    private BigDecimal tryResolveStepSize(String ex, NetworkType net, String symbol) {
        SymbolDescriptor descriptor = resolveSymbolDescriptor(ex, net, symbol);
        if (descriptor == null) {
            return null;
        }

        BigDecimal stepSize = descriptor.stepSize();
        return QtyMath.isPositive(stepSize) ? stepSize : null;
    }

    private BigDecimal tryResolveMinNotional(String ex, NetworkType net, String symbol) {
        SymbolDescriptor descriptor = resolveSymbolDescriptor(ex, net, symbol);
        if (descriptor == null) {
            return null;
        }

        BigDecimal minNotional = descriptor.minNotional();
        return QtyMath.isPositive(minNotional) ? minNotional : null;
    }

    private SymbolDescriptor resolveSymbolDescriptor(String exchangeName, NetworkType networkType, String symbol) {
        if (marketSymbolService == null) return null;
        if (exchangeName == null || exchangeName.isBlank() || symbol == null || symbol.isBlank()) return null;

        NetworkType net = (networkType != null ? networkType : NetworkType.MAINNET);
        String sym = normalizeSymbol(symbol);
        if (sym == null) return null;

        LinkedHashSet<String> accountAssets = new LinkedHashSet<>();
        for (String quote : List.of("USDT", "USDC", "BUSD", "FDUSD", "BTC", "ETH", "BNB", "EUR", "TRY", "USDP", "DAI")) {
            if (sym.endsWith(quote)) {
                accountAssets.add(quote);
            }
        }

        accountAssets.add("USDT");
        accountAssets.add("USDC");
        accountAssets.add("FDUSD");
        accountAssets.add("BUSD");
        accountAssets.add("BTC");
        accountAssets.add("ETH");
        accountAssets.add("BNB");

        for (String accountAsset : accountAssets) {
            try {
                SymbolDescriptor d = marketSymbolService.getSymbolInfo(exchangeName, net, accountAsset, sym);
                if (d != null) {
                    if (d.minNotional() == null) {
                        log.debug("ℹ️ SymbolDescriptor найден, но minNotional пустой ex={} net={} symbol={} accountAsset={}",
                                exchangeName, net, sym, accountAsset);
                    } else {
                        log.debug("✅ SymbolDescriptor найден ex={} net={} symbol={} accountAsset={} minNotional={} stepSize={} tickSize={}",
                                exchangeName, net, sym, accountAsset, QtyMath.strip(d.minNotional()), QtyMath.strip(d.stepSize()), QtyMath.strip(d.tickSize()));
                    }
                    return d;
                }
            } catch (Exception e) {
                log.debug("⚠️ SymbolDescriptor lookup failed ex={} net={} symbol={} accountAsset={} err={}",
                        exchangeName, net, sym, accountAsset, e.toString());
            }
        }

        log.warn("⚠️ Не удалось определить ограничения символа ex={} net={} symbol={} triedAccountAssets={}",
                exchangeName, net, sym, String.join(",", accountAssets));
        return null;
    }

    private boolean isRestoreSuppressedInStore(Long chatId,
                                               StrategyType strategyType,
                                               String exchange,
                                               NetworkType network,
                                               String symbol) {
        try {
            if (positionStore instanceof InMemoryPositionStoreImpl inMemoryPositionStore) {
                return inMemoryPositionStore.isRestoreSuppressed(chatId, strategyType, exchange, network, symbol);
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void suppressRestoreInStore(Long chatId,
                                        StrategyType strategyType,
                                        String exchange,
                                        NetworkType network,
                                        String symbol,
                                        String reason) {
        try {
            if (positionStore instanceof InMemoryPositionStoreImpl inMemoryPositionStore) {
                long suppressMs = Math.max(60_000L, dustRestoreSuppressMs > 0 ? dustRestoreSuppressMs : DEFAULT_DUST_RESTORE_SUPPRESS_MS);
                inMemoryPositionStore.suppressRestore(chatId, strategyType, exchange, network, symbol, suppressMs, reason);
            }
        } catch (Exception ignored) {
        }
    }

    private Optional<RecoveredPosition> alignRecoveredPositionToExchangeBalance(Long chatId,
                                                                                StrategyType strategyType,
                                                                                String exchange,
                                                                                NetworkType network,
                                                                                String symbol,
                                                                                RecoveredPosition recoveredPosition) {
        if (recoveredPosition == null) {
            return Optional.empty();
        }

        BigDecimal entryPrice = positiveOrNull(recoveredPosition.entryPrice());
        BigDecimal restoredQty = positiveOrNull(recoveredPosition.qty());
        if (entryPrice == null || restoredQty == null) {
            return Optional.empty();
        }

        BigDecimal baseFree = resolveFreeBaseQty(chatId, strategyType, null, exchange, network, symbol);
        if (baseFree == null) {
            return Optional.of(recoveredPosition);
        }

        BigDecimal usableFree = normalizeRuntimeQty(baseFree, exchange, network, symbol);
        if (!QtyMath.isPositive(usableFree)) {
            log.warn("🧹 [Восстановление] Пропускаю фантомную позицию: на бирже нет свободного base актива | chatId={} type={} ex={} net={} sym={} restoredQty={} baseFree={}",
                    chatId,
                    strategyType,
                    exchange,
                    network,
                    symbol,
                    QtyMath.strip(restoredQty),
                    QtyMath.strip(baseFree));
            suppressRestoreInStore(chatId, strategyType, exchange, network, symbol, "restore_no_base_balance");
            return Optional.empty();
        }

        BigDecimal alignedQty = restoredQty.min(usableFree);
        alignedQty = normalizeRuntimeQty(alignedQty, exchange, network, symbol);
        if (!QtyMath.isPositive(alignedQty)) {
            log.warn("🧹 [Восстановление] Пропускаю позицию после округления по step | chatId={} type={} ex={} net={} sym={} restoredQty={} baseFree={}",
                    chatId,
                    strategyType,
                    exchange,
                    network,
                    symbol,
                    QtyMath.strip(restoredQty),
                    QtyMath.strip(baseFree));
            suppressRestoreInStore(chatId, strategyType, exchange, network, symbol, "restore_qty_zero_after_step");
            return Optional.empty();
        }

        BigDecimal quoteSpent = entryPrice.multiply(alignedQty).stripTrailingZeros();
        if (quoteSpent.compareTo(MIN_RESTORABLE_NOTIONAL) < 0) {
            log.warn("🧹 [Восстановление] Пропускаю dust-позицию после сверки с балансом | chatId={} type={} ex={} net={} sym={} qty={} notional={} floor={}",
                    chatId,
                    strategyType,
                    exchange,
                    network,
                    symbol,
                    QtyMath.strip(alignedQty),
                    QtyMath.strip(quoteSpent),
                    QtyMath.strip(MIN_RESTORABLE_NOTIONAL));
            suppressRestoreInStore(chatId, strategyType, exchange, network, symbol, "restore_dust_after_balance_check");
            return Optional.empty();
        }

        if (alignedQty.compareTo(restoredQty) < 0) {
            log.warn("⚠️ [Восстановление] Ограничиваю qty по фактическому base балансу | chatId={} type={} ex={} net={} sym={} restoredQty={} baseFree={} alignedQty={}",
                    chatId,
                    strategyType,
                    exchange,
                    network,
                    symbol,
                    QtyMath.strip(restoredQty),
                    QtyMath.strip(baseFree),
                    QtyMath.strip(alignedQty));
        }

        return Optional.of(new RecoveredPosition(
                entryPrice,
                alignedQty.stripTrailingZeros(),
                quoteSpent,
                recoveredPosition.entryOrderId(),
                recoveredPosition.openedAt(),
                recoveredPosition.tp(),
                recoveredPosition.sl()
        ));
    }

    
    private BigDecimal resolveFreeBaseQty(Long chatId,
                                          StrategyType strategyType,
                                          StrategySettings ss,
                                          String ex,
                                          NetworkType net,
                                          String symbol) {

        if (accountBalanceService == null) return null;

        String baseAsset = guessBaseAsset(symbol);
        if (baseAsset == null) return null;

        try {
            AccountBalanceSnapshot snap = accountBalanceService.getSnapshot(chatId, strategyType, ex, net);
            if (snap == null || !snap.isConnectionOk()) return null;

            AccountBalanceSnapshot.AssetBalance balance = snap.getBalance(baseAsset);
            if (balance == null && snap.getBalances() != null) {
                balance = snap.getBalances().get(baseAsset.toUpperCase(Locale.ROOT));
            }
            if (balance == null && snap.getFullBalance() != null) {
                balance = snap.getFullBalance().get(baseAsset.toUpperCase(Locale.ROOT));
            }

            return balance != null ? balance.getFreeSafe() : null;

        } catch (Exception e) {
            log.debug("Не удалось получить свободный base-баланс | chatId={} type={} ex={} net={} sym={} err={}",
                    chatId, strategyType, ex, net, symbol, e.toString());
            return null;
        }
    }


    private String guessBaseAsset(String symbol) {
        String s = normalizeSymbol(symbol);
        if (s == null) return null;

        String[] quotes = new String[]{"USDT","USDC","BUSD","FDUSD","TUSD","BTC","ETH","EUR","TRY","BRL","GBP","UAH","PLN"};
        for (String q : quotes) {
            if (s.endsWith(q) && s.length() > q.length()) {
                return s.substring(0, s.length() - q.length());
            }
        }
        return null;
    }

    


    


    


    private BigDecimal normalizeRuntimeQty(BigDecimal qty,
                                           String exchange,
                                           NetworkType network,
                                           String symbol) {
        if (!QtyMath.isPositive(qty)) return BigDecimal.ZERO;

        BigDecimal stepSize = tryResolveStepSize(exchange, network, symbol);
        if (!QtyMath.isPositive(stepSize)) {
            return qty.stripTrailingZeros();
        }

        BigDecimal floored = QtyMath.floorToStepOrZero(qty, stepSize);
        return QtyMath.isPositive(floored) ? floored : qty.stripTrailingZeros();
    }

    private void persistEntryRisk(Long orderId,
                                  String exchange,
                                  NetworkType network,
                                  BigDecimal tp,
                                  BigDecimal sl) {
        if (orderId == null || orderRepository == null) return;

        try {
            orderRepository.findById(orderId).ifPresent(entity -> {
                boolean changed = false;

                String ex = safeExchange(exchange);
                if (ex != null && !ex.equalsIgnoreCase(entity.getExchangeName())) {
                    entity.setExchangeName(ex);
                    changed = true;
                }

                String net = (network != null ? network.name() : null);
                if (net != null && !net.equalsIgnoreCase(safe(entity.getNetworkType()))) {
                    entity.setNetworkType(net);
                    changed = true;
                }

                if (tp != null && tp.signum() > 0 &&
                    (entity.getTakeProfitPrice() == null || entity.getTakeProfitPrice().compareTo(tp) != 0)) {
                    entity.setTakeProfitPrice(tp);
                    changed = true;
                }

                if (sl != null && sl.signum() > 0 &&
                    (entity.getStopLossPrice() == null || entity.getStopLossPrice().compareTo(sl) != 0)) {
                    entity.setStopLossPrice(sl);
                    changed = true;
                }

                if (changed) {
                    orderRepository.save(entity);
                }
            });
        } catch (Exception e) {
            log.warn("⚠️ [Вход] Не удалось дописать TP/SL/context в orders id={} err={}", orderId, e.toString());
        }
    }

    private String resolveExchange(StrategySettings ss) {
        String fromDb = (ss != null ? safeExchange(ss.getExchangeName()) : null);
        String fromDefault = safeExchange(defaultExchange);
        return fromDb != null ? fromDb : fromDefault;
    }

    private NetworkType resolveNetwork(StrategySettings ss) {
        if (ss != null && ss.getNetworkType() != null) {
            return ss.getNetworkType();
        }
        return parseNetworkOrNull(defaultNetwork);
    }

    private static NetworkType parseNetworkOrNull(String value) {
        String s = safeExchange(value);
        if (s == null) return null;
        try {
            return NetworkType.valueOf(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String restoreProbeKey(Long chatId,
                                   StrategyType strategyType,
                                   String exchange,
                                   NetworkType network,
                                   String symbol) {
        return "RESTORE:" + entryKey(chatId, strategyType, exchange, network, symbol);
    }

    private boolean isRestoreProbeBlocked(String key, Instant now) {
        if (key == null || now == null) return false;
        Instant until = restoreMissUntil.get(key);
        return until != null && now.isBefore(until);
    }

    private void rememberRestoreMiss(String key, Instant now) {
        if (key == null || now == null) return;
        long cooldownMs = Math.max(500L, restoreRetryCooldownMs);
        restoreMissUntil.put(key, now.plusMillis(cooldownMs));
    }

    private void clearRestoreProbeState(String key) {
        if (key == null) return;
        restoreMissUntil.remove(key);
        dustRestoreLogTimes.remove(key);
    }

    private boolean shouldLogDustRestoreNow(String key, Instant now) {
        if (key == null || now == null) return true;

        long throttleMs = Math.max(1_000L, dustRestoreLogThrottleMs);
        Instant prev = dustRestoreLogTimes.get(key);
        if (prev != null) {
            long ageMs = Duration.between(prev, now).toMillis();
            if (ageMs >= 0 && ageMs < throttleMs) {
                return false;
            }
        }

        dustRestoreLogTimes.put(key, now);
        return true;
    }

    private static String safe(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String safeExchange(String exchange) {
        if (exchange == null) return null;
        String s = exchange.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normalizeUpperNullable(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty()) return null;
        return v.toUpperCase(Locale.ROOT);
    }

    private static boolean isValidPct(BigDecimal pct) {
        if (pct == null) return false;
        if (pct.signum() <= 0) return false;
        return pct.compareTo(BigDecimal.valueOf(100)) < 0;
    }

    public static BigDecimal positiveOrNull(BigDecimal v) {
        return (v != null && v.signum() > 0) ? v : null;
    }

    private BigDecimal calcTp(BigDecimal entryPrice, BigDecimal tpPct) {
        BigDecimal k = tpPct.divide(BigDecimal.valueOf(100), 12, RoundingMode.HALF_UP);
        return entryPrice.multiply(BigDecimal.ONE.add(k)).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal calcSl(BigDecimal entryPrice, BigDecimal slPct) {
        BigDecimal k = slPct.divide(BigDecimal.valueOf(100), 12, RoundingMode.HALF_UP);
        return entryPrice.multiply(BigDecimal.ONE.subtract(k)).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private static String entryKey(Long chatId,
                                   StrategyType type,
                                   String exchange,
                                   NetworkType network,
                                   String symbol) {

        String ex = (exchange == null ? "NA" : exchange.trim().toUpperCase(Locale.ROOT));
        String sym = (symbol == null ? "NA" : symbol.trim().toUpperCase(Locale.ROOT));
        String t = (type == null ? "NA" : String.valueOf(type).trim().toUpperCase(Locale.ROOT));
        String n = (network == null ? "NA" : String.valueOf(network).trim().toUpperCase(Locale.ROOT));

        return chatId + ":" + t + ":" + ex + ":" + n + ":" + sym;
    }

    private static String mapTradeErrorCode(Exception e) {
        String m = (e.getMessage() != null ? e.getMessage() : e.toString());
        String s = m.toLowerCase(Locale.ROOT);

        if (s.contains("step") || s.contains("lot") || s.contains("после округления")) return "lot_step";
        if (s.contains("notional") || s.contains("minnotional") || s.contains("min_notional")) return "min_notional";
        if (s.contains("insufficient") || s.contains("balance") || s.contains("недостат")) return "balance";
        if (s.contains("precision")) return "precision";
        if (s.contains("timeout")) return "timeout";
        if (s.contains("too many request") || s.contains("rate limit")) return "rate_limit";
        if (s.contains("connection") || s.contains("network") || s.contains("failed to connect")) return "network";
        if (s.contains("rejected") || s.contains("reject") || s.contains("filter failure")) return "exchange_reject";

        return "trade_error";
    }

    
    private BigDecimal pickExecutedQty(Order order, BigDecimal fallbackPlannedQty) {
        if (order == null) return fallbackPlannedQty;

        if (order.getExecutedQty() != null && order.getExecutedQty().signum() > 0) {
            return order.getExecutedQty();
        }
        if (order.getQuantity() != null && order.getQuantity().signum() > 0) {
            return order.getQuantity();
        }

        return fallbackPlannedQty;
    }


    
    private BigDecimal pickExecutedPrice(Order order, BigDecimal fallbackTickPrice) {
        if (order == null) return fallbackTickPrice;

        if (order.getAvgPrice() != null && order.getAvgPrice().signum() > 0) {
            return order.getAvgPrice().setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        }
        if (order.getPrice() != null && order.getPrice().signum() > 0) {
            return order.getPrice().setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        }

        return fallbackTickPrice;
    }


    
    private BigDecimal resolveCommissionPctOrNull(Long chatId,
                                                  String exchange,
                                                  NetworkType network,
                                                  StrategySettings ss) {
        if (chatId == null || exchange == null || network == null || accountBalanceService == null) {
            return null;
        }

        try {
            AccountFees fees = accountBalanceService.getAccountFees(chatId, exchange, network);
            if (fees == null) return null;

            if (fees.getTakerPct() != null && fees.getTakerPct().signum() > 0) {
                return fees.getTakerPct();
            }
            if (fees.getMakerPct() != null && fees.getMakerPct().signum() > 0) {
                return fees.getMakerPct();
            }
            return null;
        } catch (Exception e) {
            log.debug("Не удалось получить комиссию аккаунта | chatId={} ex={} net={} err={}",
                    chatId, exchange, network, e.toString());
            return null;
        }
    }


    


    


    private BigDecimal calcPnlPct(BigDecimal entryPrice, BigDecimal exitPrice) {
        if (entryPrice == null || entryPrice.signum() <= 0) return null;
        if (exitPrice == null || exitPrice.signum() <= 0) return null;

        BigDecimal r = exitPrice.divide(entryPrice, 12, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100));

        return r.setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeTradableQty(BigDecimal qty, BigDecimal stepSize) {
        if (!QtyMath.isPositive(qty)) return BigDecimal.ZERO;

        if (!QtyMath.isPositive(stepSize)) {
            return qty.stripTrailingZeros();
        }

        BigDecimal floored = QtyMath.floorToStepOrZero(qty, stepSize);
        return QtyMath.isPositive(floored) ? floored.stripTrailingZeros() : BigDecimal.ZERO;
    }
}


