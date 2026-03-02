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
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

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

    /**
     * true  -> PAPER = симуляция (без реальных ордеров), но позиции/выходы считаем и рисуем
     * false -> PAPER = реальные ордера (и на тестнете, и на майнете)
     */
    @Value("${trade.paper.blocksRealOrders:true}")
    private boolean paperBlocksRealOrders;

    @PostConstruct
    public void onStart() {
        log.info("✅ [Трейд] TradeExecutionServiceImpl активен | paperBlocksRealOrders={}", paperBlocksRealOrders);
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

        // SPOT: вход только BUY (diffPct > 0)
        if (diffPct == null || diffPct.signum() <= 0) {
            if (log.isDebugEnabled()) {
                log.debug("⛔ [Вход] Пропуск: SPOT вход только BUY | diffPct={} | chatId={} {} {}",
                        diffPct, chatId, strategyType, sym);
            }
            return EntryResult.fail("spot_entry_only_buy");
        }

        String ex = safeExchange(ss.getExchangeName());
        NetworkType net = ss.getNetworkType();

        if (ex == null) {
            log.warn("⛔ [Вход] Пропуск: exchangeName пустой в StrategySettings | chatId={} type={} sym={}", chatId, strategyType, sym);
            return EntryResult.fail("exchangeName пустой в StrategySettings");
        }
        if (net == null) {
            log.warn("⛔ [Вход] Пропуск: networkType пустой в StrategySettings | chatId={} type={} sym={}", chatId, strategyType, sym);
            return EntryResult.fail("networkType пустой в StrategySettings");
        }

        if (!isValidPct(tpPct)) {
            log.warn("⛔ [Вход] Пропуск: takeProfitPct некорректен | tpPct={} | chatId={} {} {}", tpPct, chatId, strategyType, sym);
            return EntryResult.fail("takeProfitPct_invalid");
        }
        if (!isValidPct(slPct)) {
            log.warn("⛔ [Вход] Пропуск: stopLossPct некорректен | slPct={} | chatId={} {} {}", slPct, chatId, strategyType, sym);
            return EntryResult.fail("stopLossPct_invalid");
        }

        // =====================================================
        // Фаза
        // =====================================================
        String phase = normalizeUpperNullable(ss.getRunPhase());

        if (PHASE_BACKTEST.equals(phase)) return EntryResult.fail("runPhase=BACKTEST");

        boolean collectMode = PHASE_COLLECT.equals(phase);
        boolean paperMode   = PHASE_PAPER.equals(phase);

        // =====================================================
        // ML Gate
        // =====================================================
        if (ss.isMlGateEnabled()) {
            AdvancedControlMode mode = ss.getAdvancedControlMode() != null ? ss.getAdvancedControlMode() : AdvancedControlMode.MANUAL;
            if (mode != AdvancedControlMode.MANUAL) {
                BigDecimal minProb = ss.getGateMinProb();
                if (minProb != null && minProb.signum() > 0) {
                    BigDecimal conf = (ss.getMlConfidence() != null ? ss.getMlConfidence() : BigDecimal.ZERO);
                    if (conf.compareTo(minProb) < 0) {
                        safeLive(() -> live.pushSignal(chatId, strategyType, sym, null, Signal.hold("ml_gate_reject")));
                        if (log.isDebugEnabled()) {
                            log.debug("⛔ [Вход] ML Gate отклонил вход | conf={} < minProb={} | chatId={} {} {} ex={} net={}",
                                    QtyMath.strip(conf), QtyMath.strip(minProb), chatId, strategyType, sym, ex, net);
                        }
                        return EntryResult.fail("ml_gate_reject");
                    }
                }
            }
        }

        if (positionStore.isInPosition(chatId, strategyType, ex, net, sym)) {
            if (log.isDebugEnabled()) log.debug("⛔ [Вход] Уже в позиции | chatId={} {} {} ex={} net={}", chatId, strategyType, sym, ex, net);
            return EntryResult.fail("already_in_position");
        }

        final String key = entryKey(chatId, strategyType, ex, net, sym);
        if (failCooldown.isBlocked(key)) {
            long leftMs = failCooldown.remainingMs(key);
            if (leftMs > 0) log.debug("⛔ [Вход] Кулдаун после ошибок | leftMs={} | chatId={} {} {} ex={} net={}", leftMs, chatId, strategyType, sym, ex, net);
            return EntryResult.fail("cooldown");
        }

        // =====================================================
        // Бюджет (quoteAmount)
        // =====================================================
        QuoteBudget budget = resolveQuoteBudget(chatId, strategyType, ss, ex, net);
        if (!QtyMath.isPositive(budget.quoteAmount())) {
            log.warn("⛔ [Вход] Нет бюджета для сделки | reason={} | mode={} value={} free={} | chatId={} {} {} ex={} net={}",
                    budget.reason(),
                    budget.mode(),
                    QtyMath.strip(budget.value()),
                    QtyMath.strip(budget.free()),
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

        // =====================================================
        // Защита от “TP меньше комиссий”
        // =====================================================
        BigDecimal commissionPct = resolveCommissionPctOrNull(ss);
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

        // =====================================================
        // COLLECT -> без сделок, только лог
        // =====================================================
        if (collectMode) {
            log.info("🧪 [Вход][COLLECT] {} qty={} price={} quote={} tp={} sl={} | mode={} value={} free={} | chatId={} ex={} net={}",
                    sym,
                    plannedQty.stripTrailingZeros().toPlainString(),
                    price.stripTrailingZeros().toPlainString(),
                    quoteAmount.stripTrailingZeros().toPlainString(),
                    tp.stripTrailingZeros().toPlainString(),
                    sl.stripTrailingZeros().toPlainString(),
                    budget.mode(),
                    QtyMath.strip(budget.value()),
                    QtyMath.strip(budget.free()),
                    chatId, ex, net
            );
            return EntryResult.ok(false, "BUY", plannedQty.stripTrailingZeros(), price, tp, sl, null);
        }

        // =====================================================
        // PAPER (симуляция или реальные ордера — зависит от флага)
        // =====================================================
        if (paperMode && paperBlocksRealOrders) {
            // симуляция: позицию в сторе открываем, чтобы стратегия реально “торговала” на графике
            positionStore.markOpened(
                    chatId, strategyType, ex, net, sym,
                    price, plannedQty, tp, sl,
                    quoteAmount, null, (time != null ? time : Instant.now())
            );

            log.info("📄 [Вход][PAPER-СИМ] BUY {} qty={} price={} quote={} tp={} sl={} | chatId={} ex={} net={}",
                    sym,
                    plannedQty.stripTrailingZeros().toPlainString(),
                    price.stripTrailingZeros().toPlainString(),
                    quoteAmount.stripTrailingZeros().toPlainString(),
                    tp.stripTrailingZeros().toPlainString(),
                    sl.stripTrailingZeros().toPlainString(),
                    chatId, ex, net
            );

            return EntryResult.ok(false, "BUY", plannedQty.stripTrailingZeros(), price, tp, sl, null);
        }

        // =====================================================
        // REAL ORDER
        // =====================================================
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
            log.info("▶️ [Вход] Отправляю MARKET BUY | {} quote={} price={} tpPct={} slPct={} phase={} | chatId={} ex={} net={}",
                    sym,
                    quoteAmount.stripTrailingZeros().toPlainString(),
                    price.stripTrailingZeros().toPlainString(),
                    tpPct.stripTrailingZeros().toPlainString(),
                    slPct.stripTrailingZeros().toPlainString(),
                    phase,
                    chatId, ex, net
            );

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

            log.info("✅ [Вход] BUY исполнен | {} qty={} entryPrice={} tp={} sl={} orderId={} | chatId={} ex={} net={}",
                    sym,
                    executedQty.stripTrailingZeros().toPlainString(),
                    executedPrice.stripTrailingZeros().toPlainString(),
                    tpExec.stripTrailingZeros().toPlainString(),
                    slExec.stripTrailingZeros().toPlainString(),
                    orderId,
                    chatId, ex, net
            );

            return EntryResult.ok(true, "BUY", executedQty.stripTrailingZeros(), executedPrice, tpExec, slExec, orderId);

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

        // Если не передали — добираем из БД
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

        String phase = (ss != null ? normalizeUpperNullable(ss.getRunPhase()) : null);
        boolean collectMode = PHASE_COLLECT.equals(phase);
        boolean paperMode   = PHASE_PAPER.equals(phase);

        if (PHASE_BACKTEST.equals(phase)) return ExitResult.fail("runPhase=BACKTEST");
        if (collectMode) {
            try { positionStore.clearPosition(chatId, strategyType, ex, net, sym); } catch (Exception ignored) {}
            return ExitResult.fail("collect_mode");
        }

        final String exitKey = entryKey(chatId, strategyType, ex, net, sym) + EXIT_KEY_SUFFIX;
        if (failCooldown.isBlocked(exitKey)) {
            long leftMs = failCooldown.remainingMs(exitKey);
            if (leftMs > 0) log.debug("⛔ [Выход] Кулдаун после ошибок | leftMs={} | chatId={} {} {} ex={} net={}", leftMs, chatId, strategyType, sym, ex, net);
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

        if (effQty == null || effQty.signum() <= 0) return ExitResult.fail("entryQty_invalid");
        if (effTp == null || effSl == null) return ExitResult.fail("tp/sl_null");

        boolean tpHit = price.compareTo(effTp) >= 0;
        boolean slHit = price.compareTo(effSl) <= 0;
        if (!tpHit && !slHit) return ExitResult.fail("not_hit");

        if (posOpt.isEmpty()) {
            failCooldown.recordFailure(exitKey, "no_real_position", "no snapshot in PositionStore");
            log.warn("⛔ [Выход] Пропуск: нет позиции в PositionStore | {} | chatId={} ex={} net={} tpHit={} slHit={}",
                    sym, chatId, ex, net, tpHit, slHit);
            try { positionStore.clearPosition(chatId, strategyType, ex, net, sym); } catch (Exception ignored) {}
            return ExitResult.fail("no_real_position");
        }

        // =====================================================
        // PAPER (симуляция)
        // =====================================================
        if (paperMode && paperBlocksRealOrders) {
            BigDecimal executedExitPrice = price;
            BigDecimal pnlPct = calcPnlPct(entryPriceFromStore, executedExitPrice);

            safeLive(() -> live.clearTpSl(chatId, strategyType, sym));
            safeLive(() -> live.clearPriceLines(chatId, strategyType, sym));
            safeLive(() -> live.pushSignal(chatId, strategyType, sym, null,
                    Signal.sell(executedExitPrice.doubleValue(), tpHit ? "TP" : "SL")));

            log.info("📄 [Выход][PAPER-СИМ] SELL {} qty={} exitPrice={} reason={} pnlPct={} | chatId={} ex={} net={}",
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
                    (time != null ? time : Instant.now()),
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

        // =====================================================
        // REAL EXIT
        // =====================================================
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

        // FREE баланс базового актива (если смогли определить) — иначе НЕ блокируем выход
        BigDecimal freeBase = resolveFreeBaseQty(chatId, strategyType, ss, ex, net, sym);

        if (freeBase != null) {
            if (QtyMath.isPositive(freeBase)) {
                if (freeBase.compareTo(effQty) < 0) {
                    log.warn("⚠️ [Выход] Корректирую qty по free балансу | {} baseFree={} plannedQty={} tpHit={} slHit={} | chatId={} ex={} net={}",
                            sym, QtyMath.strip(freeBase), QtyMath.strip(effQty), tpHit, slHit, chatId, ex, net);
                    effQty = freeBase;
                }
            } else {
                failCooldown.recordFailure(exitKey, "balance", "base_free=0 for " + sym);
                log.error("⛔ [Выход] НЕТ base free баланса | {} qty={} tpHit={} slHit={} | chatId={} ex={} net={}",
                        sym, QtyMath.strip(effQty), tpHit, slHit, chatId, ex, net);
                try { positionStore.clearPosition(chatId, strategyType, ex, net, sym); } catch (Exception ignored) {}
                return ExitResult.fail("balance");
            }
        }

        // stepSize-normalize перед SELL
        BigDecimal stepSize = tryResolveStepSize(ex, net, sym);
        if (QtyMath.isPositive(stepSize)) {
            BigDecimal floored = QtyMath.floorToStepOrZero(effQty, stepSize);

            if (!QtyMath.isPositive(floored)) {
                String msg = "qty меньше stepSize (" + QtyMath.strip(stepSize) + ")";
                failCooldown.recordFailure(exitKey, "lot_step", msg);
                log.warn("⛔ [Выход] DUST (qty слишком маленький) | {} qty={} stepSize={} tpHit={} slHit={} | chatId={} ex={} net={}",
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
                    (time != null ? time : Instant.now()),
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

            // AUTO-TUNE: только на SL/убытке
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

            if ("lot_step".equals(code) || "min_notional".equals(code) || "balance".equals(code)) {
                failCooldown.recordFailure(exitKey, code, e.getMessage());
            }

            log.error("💥 [Выход] SELL не удался | {} | code={} | chatId={} ex={} net={} | err={}",
                    sym, code, chatId, ex, net, e.toString(), e);
            return ExitResult.fail(code);
        }
    }

    // =====================================================
    // TradeClosedEvent
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
            log.warn("⚠️ [Трейд] TradeClosedEvent publish failed chatId={} type={} sym={} err={}",
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
     * gate для тюнинга/переобучения:
     *  - MANUAL -> false
     *  - autoTuneEnabled=false -> false (через reflection)
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

            return readBool(s,
                    "autoTuneEnabled", "isAutoTuneEnabled", "getAutoTuneEnabled",
                    "mlAutoTuneEnabled", "isMlAutoTuneEnabled", "getMlAutoTuneEnabled"
            );
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
            switch (r) {
                case null -> { return null; }
                case BigDecimal bd -> { return bd; }
                case Number n -> { return BigDecimal.valueOf(n.doubleValue()); }
                default -> {}
            }
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

            Object balObj = reflectObj(snap, "getBalance", new Class<?>[]{String.class}, new Object[]{baseAsset});
            BigDecimal free = extractFreeFromBalanceObj(balObj);
            if (QtyMath.isPositive(free) || free != null) return free;

            balObj = reflectObj(snap, "getAssetBalance", new Class<?>[]{String.class}, new Object[]{baseAsset});
            free = extractFreeFromBalanceObj(balObj);
            if (QtyMath.isPositive(free) || free != null) return free;

            Object mapObj = reflectObj(snap, "getBalances", new Class<?>[]{}, new Object[]{});
            free = extractFreeFromBalancesMap(mapObj, baseAsset);
            if (QtyMath.isPositive(free) || free != null) return free;

            mapObj = reflectObj(snap, "getFullBalance", new Class<?>[]{}, new Object[]{});
            free = extractFreeFromBalancesMap(mapObj, baseAsset);
            return free;

        } catch (Exception ignored) {
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
        if (QtyMath.isPositive(free) || free != null) return free;

        free = reflectBd(balObj, "getAvailable", new Class<?>[]{}, new Object[]{});
        if (QtyMath.isPositive(free) || free != null) return free;

        free = reflectBd(balObj, "getFreeQty", new Class<?>[]{}, new Object[]{});
        return free;
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
                switch (v) {
                    case null -> { continue; }
                    case BigDecimal bd -> { return bd; }
                    case Number n -> { return BigDecimal.valueOf(n.doubleValue()); }
                    default -> {}
                }
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
    // reflection bool helper
    // =====================================================

    private static boolean readBool(Object obj, String... names) {
        if (obj == null) return false;
        for (String n : names) {
            if (n == null || n.isBlank()) continue;
            try {
                Method m = findNoArgMethod(obj.getClass(), n);
                if (m == null) continue;
                Object v = m.invoke(obj);
                switch (v) {
                    case null -> { continue; }
                    case Boolean b -> { return b; }
                    case Number num -> { return num.intValue() != 0; }
                    default -> {}
                }
                String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
                return "true".equals(s) || "1".equals(s) || "yes".equals(s);
            } catch (Exception ignored) {}
        }
        return false;
    }

    private static Method findNoArgMethod(Class<?> c, String name) {
        try { return c.getMethod(name); } catch (Exception ignored) {}
        String cap = !name.isEmpty() ? Character.toUpperCase(name.charAt(0)) + name.substring(1) : name;
        try { return c.getMethod("get" + cap); } catch (Exception ignored) {}
        try { return c.getMethod("is" + cap); } catch (Exception ignored) {}
        return null;
    }
}