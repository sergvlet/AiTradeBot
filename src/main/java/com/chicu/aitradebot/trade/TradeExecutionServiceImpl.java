// src/main/java/com/chicu/aitradebot/trade/TradeExecutionServiceImpl.java
package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.account.AccountBalanceService;
import com.chicu.aitradebot.account.AccountBalanceSnapshot;
import com.chicu.aitradebot.ai.runtime.MlAutoTuneRuntime;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.trade.math.QtyMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeExecutionServiceImpl implements TradeExecutionService {

    private static final int QTY_SCALE = 8;
    private static final int PRICE_SCALE = 8;

    private static final String PHASE_COLLECT  = "COLLECT";
    private static final String PHASE_BACKTEST = "BACKTEST";
    private static final String PHASE_PAPER    = "PAPER";

    /** Мини-буфер сверх комиссий (в процентах), чтобы TP не был "в ноль". */
    private static final BigDecimal TP_FEE_BUFFER_PCT = new BigDecimal("0.02"); // 0.02%

    /** Анти-спам для EXIT, если биржа/guard блокирует (lot_step / min_notional и т.п.) */
    private static final String EXIT_KEY_SUFFIX = ":EXIT";

    private final OrderService orderService;
    private final StrategyLivePublisher live;
    private final AccountBalanceService accountBalanceService;

    private final StrategySettingsService settingsService;
    private final TradeFailCooldownService failCooldown;
    private final PositionStore positionStore;
    private final MlAutoTuneRuntime mlAutoTuneRuntime;

    /** События (датасет/обучение/метрики) */
    private final ApplicationEventPublisher eventPublisher;

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

        // SPOT: вход только BUY (diffPct > 0)
        if (diffPct == null || diffPct.signum() <= 0) {
            return EntryResult.fail("spot_entry_only_buy");
        }

        String ex = safeExchange(ss.getExchangeName());
        NetworkType net = ss.getNetworkType();

        if (ex == null) return EntryResult.fail("exchangeName пустой в StrategySettings");
        if (net == null) return EntryResult.fail("networkType пустой в StrategySettings");

        if (!isValidPct(tpPct)) return EntryResult.fail("takeProfitPct_invalid");
        if (!isValidPct(slPct)) return EntryResult.fail("stopLossPct_invalid");

        // =====================================================
        // Фаза / режимы
        // =====================================================
        String phase = normalizeUpperNullable(ss.getRunPhase());

        if (PHASE_BACKTEST.equals(phase)) return EntryResult.fail("runPhase=BACKTEST");
        if (PHASE_PAPER.equals(phase) && net != NetworkType.TESTNET) return EntryResult.fail("paper_requires_testnet");

        boolean collectMode = PHASE_COLLECT.equals(phase);

        // =====================================================
        // ML Gate (простая версия: ss.mlConfidence vs ss.gateMinProb)
        // =====================================================
        if (ss.isMlGateEnabled()) {
            BigDecimal minProb = ss.getGateMinProb();
            if (minProb != null && minProb.signum() > 0) {
                BigDecimal conf = (ss.getMlConfidence() != null ? ss.getMlConfidence() : BigDecimal.ZERO);
                if (conf.compareTo(minProb) < 0) {
                    safeLive(() -> live.pushSignal(chatId, strategyType, sym, null, Signal.hold("ml_gate_reject")));
                    return EntryResult.fail("ml_gate_reject");
                }
            }
        }

        if (positionStore.isInPosition(chatId, strategyType, ex, net, sym)) {
            return EntryResult.fail("already_in_position");
        }

        final String key = entryKey(chatId, strategyType, ex, net, sym);
        if (failCooldown.isBlocked(key)) {
            long leftMs = failCooldown.remainingMs(key);
            if (leftMs > 0) log.debug("[TRADE] ENTRY SKIP (cooldown) key={} leftMs={}", key, leftMs);
            return EntryResult.fail("cooldown");
        }

        // =====================================================
        // Бюджет (quoteAmount)
        // =====================================================
        QuoteBudget budget = resolveQuoteBudget(chatId, strategyType, ss, ex, net);
        if (!QtyMath.isPositive(budget.quoteAmount())) {
            return EntryResult.fail(budget.reason());
        }

        BigDecimal quoteAmount = budget.quoteAmount();
        BigDecimal plannedQty = quoteAmount.divide(price, QTY_SCALE, RoundingMode.DOWN);
        if (plannedQty.signum() <= 0) return EntryResult.fail("qty=0");

        // =====================================================
        // FIX: защита от "TP меньше комиссий"
        // =====================================================
        BigDecimal commissionPct = resolveCommissionPctOrNull(ss);
        if (commissionPct != null && commissionPct.signum() > 0) {
            BigDecimal minTpToNotLose = commissionPct.multiply(BigDecimal.valueOf(2)).add(TP_FEE_BUFFER_PCT);
            if (tpPct.compareTo(minTpToNotLose) < 0) {
                log.debug("[TRADE] TP too small for fees: tpPct={} < minTp={} (feePct={} x2 + buffer={}) chatId={} {} {} {}",
                        tpPct.stripTrailingZeros().toPlainString(),
                        minTpToNotLose.stripTrailingZeros().toPlainString(),
                        commissionPct.stripTrailingZeros().toPlainString(),
                        TP_FEE_BUFFER_PCT.stripTrailingZeros().toPlainString(),
                        chatId, strategyType, ex, sym
                );
                return EntryResult.fail("tp_too_small_for_fees");
            }
        }

        BigDecimal tp = calcTp(price, tpPct);
        BigDecimal sl = calcSl(price, slPct);

        if (tp.compareTo(price) <= 0) return EntryResult.fail("tp_le_entry");
        if (sl.compareTo(price) >= 0) return EntryResult.fail("sl_ge_entry");
        if (sl.signum() <= 0) return EntryResult.fail("sl_le_0");

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

        safeLive(() -> live.pushSignal(chatId, strategyType, sym, null, Signal.buy(price.doubleValue(), "entry")));

        if (collectMode) {
            log.info("[TRADE] COLLECT ENTRY {} plannedQty={} price={} quoteAmount={} mode={} value={} free={} tpPct={} slPct={} tp={} sl={} chatId={} ex={} net={} phase={}",
                    sym,
                    plannedQty.stripTrailingZeros().toPlainString(),
                    price.stripTrailingZeros().toPlainString(),
                    quoteAmount.stripTrailingZeros().toPlainString(),
                    budget.mode(),
                    QtyMath.strip(budget.value()),
                    QtyMath.strip(budget.free()),
                    tpPct.stripTrailingZeros().toPlainString(),
                    slPct.stripTrailingZeros().toPlainString(),
                    tp.stripTrailingZeros().toPlainString(),
                    sl.stripTrailingZeros().toPlainString(),
                    chatId, ex, net, phase
            );
            return EntryResult.ok(false, "BUY", plannedQty.stripTrailingZeros(), price, tp, sl, null);
        }

        try {
            // BUY на сумму в QUOTE
            Order order = orderService.placeMarket(ctx, OrderSide.BUY, quoteAmount, price);
            Long orderId = (order != null ? order.getId() : null);

            BigDecimal executedQty = pickExecutedQty(order, plannedQty);
            BigDecimal executedPrice = pickExecutedPrice(order, price);

            BigDecimal tpExec = calcTp(executedPrice, tpPct);
            BigDecimal slExec = calcSl(executedPrice, slPct);

            failCooldown.clear(key);
            failCooldown.clear(key + EXIT_KEY_SUFFIX);

            positionStore.markOpened(
                    chatId,
                    strategyType,
                    ex,
                    net,
                    sym,
                    executedPrice,
                    executedQty,
                    tpExec,
                    slExec,
                    quoteAmount,
                    orderId,
                    (time != null ? time : Instant.now())
            );

            log.info("[TRADE] ENTRY SPOT BUY {} executedQty={} plannedQty={} entryPrice={} tickPrice={} quoteAmount={} mode={} value={} free={} tpPct={} slPct={} tp={} sl={} chatId={} ex={} net={} phase={}",
                    sym,
                    executedQty.stripTrailingZeros().toPlainString(),
                    plannedQty.stripTrailingZeros().toPlainString(),
                    executedPrice.stripTrailingZeros().toPlainString(),
                    price.stripTrailingZeros().toPlainString(),
                    quoteAmount.stripTrailingZeros().toPlainString(),
                    budget.mode(),
                    QtyMath.strip(budget.value()),
                    QtyMath.strip(budget.free()),
                    tpPct.stripTrailingZeros().toPlainString(),
                    slPct.stripTrailingZeros().toPlainString(),
                    tpExec.stripTrailingZeros().toPlainString(),
                    slExec.stripTrailingZeros().toPlainString(),
                    chatId, ex, net, phase
            );

            return EntryResult.ok(true, "BUY", executedQty.stripTrailingZeros(), executedPrice, tpExec, slExec, orderId);

        } catch (Exception e) {
            String code = mapTradeErrorCode(e);
            failCooldown.recordFailure(key, code, e.getMessage());

            log.warn("[TRADE] ENTRY FAILED {} chatId={} code={} err={}", sym, chatId, code, e.toString());
            log.debug("[TRADE] ENTRY FAILED stack", e);

            return EntryResult.fail(code);
        }
    }

    // =====================================================
    // EXIT
    // =====================================================

    /**
     * ✅ ЭТО ИМЕННО СИГНАТУРА ИНТЕРФЕЙСА (из ошибки компиляции).
     */
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

        // Если не передали — добираем из БД
        if (ss != null) {
            if (ex == null) ex = safeExchange(ss.getExchangeName());
            if (net == null) net = ss.getNetworkType();
        }

        if (ex == null) return ExitResult.fail("exchange=null");
        if (net == null) return ExitResult.fail("network=null");

        return doExitIfHitInternal(chatId, strategyType, symbol, price, time, isLong, entryQty, tp, sl, ss, ex, net);
    }

    /**
     * Удобный overload (не @Override), если у тебя где-то ещё вызывается короткая форма.
     */
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

        // Фазы / режимы
        if (ss != null) {
            String phase = normalizeUpperNullable(ss.getRunPhase());
            boolean collectMode = PHASE_COLLECT.equals(phase);

            if (PHASE_BACKTEST.equals(phase)) return ExitResult.fail("runPhase=BACKTEST");
            if (collectMode) {
                try { positionStore.clearPosition(chatId, strategyType, ex, net, sym); } catch (Exception ignored) {}
                return ExitResult.fail("collect_mode");
            }
            if (PHASE_PAPER.equals(phase) && net != NetworkType.TESTNET) {
                return ExitResult.fail("paper_requires_testnet");
            }
        }

        final String exitKey = entryKey(chatId, strategyType, ex, net, sym) + EXIT_KEY_SUFFIX;
        if (failCooldown.isBlocked(exitKey)) {
            long leftMs = failCooldown.remainingMs(exitKey);
            if (leftMs > 0) log.debug("[TRADE] EXIT SKIP (cooldown) key={} leftMs={}", exitKey, leftMs);
            return ExitResult.fail("cooldown");
        }

        // tp/sl/qty/entryPrice из PositionStore (приоритет)
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

        if (effQty == null || effQty.signum() <= 0) return ExitResult.fail("entryQty_invalid");
        if (effTp == null || effSl == null) return ExitResult.fail("tp/sl_null");

        boolean tpHit = price.compareTo(effTp) >= 0;
        boolean slHit = price.compareTo(effSl) <= 0;
        if (!tpHit && !slHit) return ExitResult.fail("not_hit");

        if (posOpt.isEmpty()) {
            failCooldown.recordFailure(exitKey, "no_real_position", "no snapshot in PositionStore");
            log.warn("[TRADE] EXIT SKIP (NO REAL POSITION) {} chatId={} ex={} net={} tpHit={} slHit={}",
                    sym, chatId, ex, net, tpHit, slHit);
            try { positionStore.clearPosition(chatId, strategyType, ex, net, sym); } catch (Exception ignored) {}
            return ExitResult.fail("no_real_position");
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

        // FREE баланс базового актива
        BigDecimal freeBase = resolveFreeBaseQty(chatId, strategyType, ss, ex, net, sym);

        if (QtyMath.isPositive(freeBase)) {
            if (freeBase.compareTo(effQty) < 0) {
                log.warn("[TRADE] EXIT ADJUST by FREE balance {} chatId={} ex={} net={} baseFree={} plannedQty={} tpHit={} slHit={}",
                        sym, chatId, ex, net, QtyMath.strip(freeBase), QtyMath.strip(effQty), tpHit, slHit);
                effQty = freeBase;
            }
        } else {
            failCooldown.recordFailure(exitKey, "balance", "base_free=0 for " + sym);
            log.error("[TRADE] EXIT BLOCKED (NO BASE FREE) {} chatId={} ex={} net={} qty={} tpHit={} slHit={}",
                    sym, chatId, ex, net, QtyMath.strip(effQty), tpHit, slHit);
            try { positionStore.clearPosition(chatId, strategyType, ex, net, sym); } catch (Exception ignored) {}
            return ExitResult.fail("balance");
        }

        // stepSize-normalize перед SELL
        BigDecimal stepSize = tryResolveStepSize(ex, net, sym);
        if (QtyMath.isPositive(stepSize)) {
            BigDecimal floored = QtyMath.floorToStepOrZero(effQty, stepSize);

            if (!QtyMath.isPositive(floored)) {
                String msg = "qty меньше stepSize (" + QtyMath.strip(stepSize) + ")";
                failCooldown.recordFailure(exitKey, "lot_step", msg);
                log.warn("[TRADE] EXIT BLOCKED (DUST) {} chatId={} ex={} net={} qty={} stepSize={} tpHit={} slHit={}",
                        sym, chatId, ex, net, QtyMath.strip(effQty), QtyMath.strip(stepSize), tpHit, slHit
                );
                return ExitResult.fail("lot_step");
            }

            effQty = floored;
        }

        if (effQty == null || effQty.signum() <= 0) {
            failCooldown.recordFailure(exitKey, "balance", "effQty<=0 after adjust");
            return ExitResult.fail("balance");
        }

        try {
            Order order = orderService.placeMarket(ctx, OrderSide.SELL, effQty, price);
            BigDecimal executedExitPrice = pickExecutedPrice(order, price);

            safeLive(() -> live.clearTpSl(chatId, strategyType, sym));
            safeLive(() -> live.clearPriceLines(chatId, strategyType, sym));
            safeLive(() -> live.pushSignal(chatId, strategyType, sym, null,
                    Signal.sell(executedExitPrice.doubleValue(), tpHit ? "TP" : "SL")));

            BigDecimal pnlPct = calcPnlPct(entryPriceFromStore, executedExitPrice);

            log.info("[TRADE] EXIT SPOT SELL {} qty={} exitPrice={} tickPrice={} tpHit={} slHit={} pnlPct={} chatId={} ex={} net={}",
                    sym,
                    effQty.stripTrailingZeros().toPlainString(),
                    executedExitPrice.stripTrailingZeros().toPlainString(),
                    price.stripTrailingZeros().toPlainString(),
                    tpHit,
                    slHit,
                    pnlPct != null ? pnlPct.stripTrailingZeros().toPlainString() : "null",
                    chatId,
                    ex,
                    net
            );

            try { positionStore.clearPosition(chatId, strategyType, ex, net, sym); } catch (Exception ignored) {}
            failCooldown.clear(exitKey);

            // ✅ EVENT (без compile-зависимости): публикуем TradeClosedEvent, если класс реально есть
            publishTradeClosedEventSafely(
                    chatId,
                    strategyType,
                    sym,
                    (ss != null ? ss.getTimeframe() : null),
                    pnlPct,
                    tpHit ? "TP" : "SL",
                    (time != null ? time : Instant.now())
            );

            // ✅ AUTO-RETRAIN/TRIGGER:
            // - триггерим тюнинг ТОЛЬКО при SL или при отрицательном PnL (даже если причина TP, но по факту минус)
            boolean isLoss = (pnlPct != null && pnlPct.signum() < 0);

            if ((slHit || isLoss) && allowAutoTune(ss)) {
                String reason = slHit ? "after-close:sl" : "after-close:loss";
                if (pnlPct != null) reason += ":pnlPct=" + pnlPct.stripTrailingZeros().toPlainString();

                // на лоссе/SL — быстро, но с мини-дебаунсом
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

            if ("lot_step".equals(code) || "min_notional".equals(code) || "balance".equals(code)) {
                failCooldown.recordFailure(exitKey, code, e.getMessage());
            }

            log.error("[TRADE] EXIT FAILED {} chatId={} code={} err={}", sym, chatId, code, e.toString(), e);
            return ExitResult.fail(code);
        }
    }

    // =====================================================
    // TradeClosedEvent via reflection (чтобы не падать компиляцией)
    // =====================================================

    private void publishTradeClosedEventSafely(Long chatId,
                                               StrategyType strategyType,
                                               String symbol,
                                               String timeframe,
                                               BigDecimal pnlPct,
                                               String exitReason,
                                               Instant closedAt) {
        if (eventPublisher == null) return;

        try {
            Class<?> cls = Class.forName("com.chicu.aitradebot.ai.ml.dataset.TradeClosedEvent");
            Constructor<?> ctor = cls.getDeclaredConstructor(
                    Long.class,
                    StrategyType.class,
                    String.class,
                    String.class,
                    BigDecimal.class,
                    String.class,
                    Instant.class
            );

            Object ev = ctor.newInstance(
                    chatId,
                    strategyType,
                    symbol,
                    timeframe,
                    pnlPct,
                    exitReason,
                    closedAt
            );

            eventPublisher.publishEvent(ev);

        } catch (ClassNotFoundException e) {
            log.debug("[TRADE] TradeClosedEvent not found on classpath -> skip");
        } catch (Exception e) {
            log.warn("[TRADE] TradeClosedEvent publish failed chatId={} type={} sym={} err={}",
                    chatId, strategyType, symbol, e.toString());
        }
    }

    // =====================================================
    // Budget resolver
    // =====================================================

    private record QuoteBudget(BigDecimal quoteAmount,
                               StrategySettings.CapitalMode mode,
                               BigDecimal value,
                               BigDecimal free,
                               String reason) {}

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

    // =====================================================
    // helpers
    // =====================================================

    private void safeLive(Runnable r) {
        try { r.run(); } catch (Exception ignored) {}
    }

    /**
     * ✅ gate для тюнинга/переобучения:
     *  - MANUAL -> false
     *  - autoTuneEnabled=false -> false (через reflection, чтобы не зависеть от точного геттера)
     *  - phase BACKTEST/COLLECT -> false
     */
    private boolean allowAutoTune(StrategySettings s) {
        try {
            if (s == null) return false;

            AdvancedControlMode mode = s.getAdvancedControlMode() != null ? s.getAdvancedControlMode() : AdvancedControlMode.MANUAL;
            if (mode == AdvancedControlMode.MANUAL) return false;

            String phase = normalizeUpperNullable(s.getRunPhase());
            boolean phaseBlocks = PHASE_BACKTEST.equals(phase) || PHASE_COLLECT.equals(phase);
            if (phaseBlocks) return false;

            boolean enabled = readBool(s,
                    "autoTuneEnabled", "isAutoTuneEnabled", "getAutoTuneEnabled",
                    "mlAutoTuneEnabled", "isMlAutoTuneEnabled", "getMlAutoTuneEnabled"
            );

            return enabled;

        } catch (Exception ignored) {
            return false;
        }
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
        if (orderService == null) return null;

        BigDecimal v = reflectBd(orderService, "getStepSize",
                new Class<?>[]{String.class, NetworkType.class, String.class},
                new Object[]{ex, net, symbol});
        if (QtyMath.isPositive(v)) return v;

        v = reflectBd(orderService, "getLotStepSize",
                new Class<?>[]{String.class, NetworkType.class, String.class},
                new Object[]{ex, net, symbol});
        if (QtyMath.isPositive(v)) return v;

        v = reflectBd(orderService, "getQtyStep",
                new Class<?>[]{String.class, NetworkType.class, String.class},
                new Object[]{ex, net, symbol});
        if (QtyMath.isPositive(v)) return v;

        v = reflectBd(orderService, "getSymbolStepSize",
                new Class<?>[]{String.class, NetworkType.class, String.class},
                new Object[]{ex, net, symbol});
        if (QtyMath.isPositive(v)) return v;

        return null;
    }

    private BigDecimal reflectBd(Object target, String method, Class<?>[] sig, Object[] args) {
        try {
            Method m = target.getClass().getMethod(method, sig);
            Object r = m.invoke(target, args);
            if (r == null) return null;
            if (r instanceof BigDecimal bd) return bd;
            if (r instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
            String s = String.valueOf(r).trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
            return new BigDecimal(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    // =====================================================
    // BASE FREE для EXIT
    // =====================================================

    private BigDecimal resolveFreeBaseQty(Long chatId,
                                          StrategyType strategyType,
                                          StrategySettings ss,
                                          String ex,
                                          NetworkType net,
                                          String symbol) {

        if (accountBalanceService == null) return null;
        if (ss == null) return null;

        String baseAsset = guessBaseAsset(symbol);
        if (baseAsset == null) return null;

        try {
            AccountBalanceSnapshot snap = accountBalanceService.getSnapshot(chatId, strategyType, ex, net);
            if (snap == null || !snap.isConnectionOk()) return null;

            Object balObj = reflectObj(snap, "getBalance",
                    new Class<?>[]{String.class},
                    new Object[]{baseAsset});
            BigDecimal free = extractFreeFromBalanceObj(balObj);
            if (QtyMath.isPositive(free)) return free;

            balObj = reflectObj(snap, "getAssetBalance",
                    new Class<?>[]{String.class},
                    new Object[]{baseAsset});
            free = extractFreeFromBalanceObj(balObj);
            if (QtyMath.isPositive(free)) return free;

            Object mapObj = reflectObj(snap, "getBalances", new Class<?>[]{}, new Object[]{});
            free = extractFreeFromBalancesMap(mapObj, baseAsset);
            if (QtyMath.isPositive(free)) return free;

            mapObj = reflectObj(snap, "getFullBalance", new Class<?>[]{}, new Object[]{});
            free = extractFreeFromBalancesMap(mapObj, baseAsset);
            if (QtyMath.isPositive(free)) return free;

        } catch (Exception ignored) {}

        return null;
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

    private Object reflectObj(Object target, String method, Class<?>[] sig, Object[] args) {
        try {
            Method m = target.getClass().getMethod(method, sig);
            return m.invoke(target, args);
        } catch (Exception ignored) {
            return null;
        }
    }

    private BigDecimal extractFreeFromBalanceObj(Object balObj) {
        if (balObj == null) return null;

        BigDecimal free = reflectBd(balObj, "getFree", new Class<?>[]{}, new Object[]{});
        if (QtyMath.isPositive(free)) return free;

        free = reflectBd(balObj, "getAvailable", new Class<?>[]{}, new Object[]{});
        if (QtyMath.isPositive(free)) return free;

        free = reflectBd(balObj, "getFreeQty", new Class<?>[]{}, new Object[]{});
        if (QtyMath.isPositive(free)) return free;

        return null;
    }

    private BigDecimal extractFreeFromBalancesMap(Object mapObj, String asset) {
        if (mapObj == null || asset == null) return null;

        if (mapObj instanceof java.util.Map<?, ?> map) {
            Object bal = map.get(asset);
            return extractFreeFromBalanceObj(bal);
        }
        return null;
    }

    // =====================================================
    // misc utils
    // =====================================================

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

        try {
            BigDecimal q = order.getQuantity();
            if (q != null && q.signum() > 0) return q;
        } catch (Exception ignored) {}

        BigDecimal fromOrder = readBd(order,
                "getExecutedQty",
                "getFilledQty",
                "getOrigQty",
                "getQty",
                "getQuantity"
        );
        if (fromOrder != null && fromOrder.signum() > 0) return fromOrder;

        return fallbackPlannedQty;
    }

    private BigDecimal pickExecutedPrice(Order order, BigDecimal fallbackTickPrice) {
        if (order == null) return fallbackTickPrice;

        BigDecimal fromOrder = readBd(order,
                "getAvgPrice",
                "getAveragePrice",
                "getExecutedPrice",
                "getFillPrice",
                "getLastFillPrice",
                "getPrice"
        );
        if (fromOrder != null && fromOrder.signum() > 0) {
            return fromOrder.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        }

        return fallbackTickPrice;
    }

    private BigDecimal resolveCommissionPctOrNull(StrategySettings ss) {
        if (ss == null) return null;

        BigDecimal v = null;
        try {
            Method m = ss.getClass().getMethod("getCommissionPct");
            Object r = m.invoke(ss);
            if (r instanceof BigDecimal bd) v = bd;
            else if (r != null) v = new BigDecimal(String.valueOf(r));
        } catch (Exception ignored) {}

        if (v != null && v.signum() > 0) return v;
        return null;
    }

    private BigDecimal readBd(Object obj, String... methods) {
        if (obj == null) return null;
        for (String m : methods) {
            try {
                Method mm = obj.getClass().getMethod(m);
                Object v = mm.invoke(obj);
                if (v == null) continue;
                if (v instanceof BigDecimal bd) return bd;
                if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
                String s = String.valueOf(v).trim();
                if (s.isEmpty() || "null".equalsIgnoreCase(s)) continue;
                return new BigDecimal(s);
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** PnL% (gross) = (exit/entry - 1) * 100 */
    private BigDecimal calcPnlPct(BigDecimal entryPrice, BigDecimal exitPrice) {
        if (entryPrice == null || entryPrice.signum() <= 0) return null;
        if (exitPrice == null || exitPrice.signum() <= 0) return null;

        BigDecimal r = exitPrice.divide(entryPrice, 12, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100));

        return r.setScale(6, RoundingMode.HALF_UP);
    }

    // =====================================================
    // reflection bool helper (чтобы не зависеть от точного геттера)
    // =====================================================

    private static boolean readBool(Object obj, String... names) {
        if (obj == null) return false;
        for (String n : names) {
            if (n == null || n.isBlank()) continue;
            try {
                Method m = findNoArgMethod(obj.getClass(), n);
                if (m == null) continue;
                Object v = m.invoke(obj);
                if (v == null) continue;
                if (v instanceof Boolean b) return b;
                if (v instanceof Number num) return num.intValue() != 0;
                String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
                return "true".equals(s) || "1".equals(s) || "yes".equals(s);
            } catch (Exception ignored) {}
        }
        return false;
    }

    private static Method findNoArgMethod(Class<?> c, String name) {
        try { return c.getMethod(name); } catch (Exception ignored) {}
        String cap = name.length() > 0 ? Character.toUpperCase(name.charAt(0)) + name.substring(1) : name;
        try { return c.getMethod("get" + cap); } catch (Exception ignored) {}
        try { return c.getMethod("is" + cap); } catch (Exception ignored) {}
        return null;
    }
}