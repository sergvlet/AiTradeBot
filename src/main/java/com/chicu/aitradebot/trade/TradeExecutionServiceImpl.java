package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.account.AccountBalanceService;
import com.chicu.aitradebot.account.AccountBalanceSnapshot;
import com.chicu.aitradebot.ai.runtime.MlAutoTuneRuntime;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    /**
     * ✅ Мини-буфер сверх комиссий (в процентах), чтобы TP не был "в ноль".
     */
    private static final BigDecimal TP_FEE_BUFFER_PCT = new BigDecimal("0.02"); // 0.02%

    private final OrderService orderService;
    private final StrategyLivePublisher live;
    private final AccountBalanceService accountBalanceService;

    // ✅ настройки стратегии (для autotune по флагу) — вызываем reflection-safe
    private final StrategySettingsService settingsService;

    // ✅ анти-спам входа при фейле
    private final TradeFailCooldownService failCooldown;

    // ✅ позиции + автотюнинг
    private final PositionStore positionStore;
    private final MlAutoTuneRuntime mlAutoTuneRuntime;

    @Override
    public EntryResult executeEntry(Long chatId,
                                    StrategyType strategyType,
                                    String symbol,
                                    BigDecimal price,
                                    BigDecimal diffPct,
                                    Instant time,
                                    StrategySettings ss) {
        // ✅ В V4 TP/SL живут ТОЛЬКО в настройках конкретной стратегии (отдельные таблицы),
        // поэтому этот overload использовать нельзя.
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

        if (price == null || price.signum() <= 0) return EntryResult.fail("price invalid");

        // SPOT: вход только BUY (diffPct > 0)
        if (diffPct == null || diffPct.signum() <= 0) {
            return EntryResult.fail("spot_entry_only_buy");
        }

        String ex = safeExchange(ss.getExchangeName());
        NetworkType net = ss.getNetworkType();

        if (ex == null) return EntryResult.fail("exchangeName пустой в StrategySettings");
        if (net == null) return EntryResult.fail("networkType пустой в StrategySettings");

        if (!isValidPct(tpPct)) return EntryResult.fail("takeProfitPct invalid");
        if (!isValidPct(slPct)) return EntryResult.fail("stopLossPct invalid");

        // =====================================================
        // ✅ Фаза / режимы (COLLECT / PAPER / BACKTEST)
        // =====================================================
        String phase = normalizeUpperNullable(ss.getRunPhase());

        if (PHASE_BACKTEST.equals(phase)) {
            return EntryResult.fail("runPhase=BACKTEST");
        }
        if (PHASE_PAPER.equals(phase) && net != NetworkType.TESTNET) {
            return EntryResult.fail("paper_requires_testnet");
        }

        boolean collectMode = ss.isCollectEnabled() || PHASE_COLLECT.equals(phase);

        // =====================================================
        // ✅ ML Gate (простая версия: ss.mlConfidence vs ss.gateMinProb)
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

        // ✅ FIX: проверка позиции должна учитывать symbol
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
        // ✅ БЮДЖЕТ в USDT (quoteAmount) — главный параметр BUY
        // =====================================================
        BigDecimal quoteAmount = resolveQuoteAmount(chatId, strategyType, ss);
        if (quoteAmount == null || quoteAmount.signum() <= 0) {
            return EntryResult.fail("no_budget");
        }

        // Плановый qty (только для UI/логов/collectMode)
        BigDecimal plannedQty = quoteAmount.divide(price, QTY_SCALE, RoundingMode.DOWN);
        if (plannedQty.signum() <= 0) return EntryResult.fail("qty=0");

        // =====================================================
        // ✅ FIX: защита от "TP меньше комиссий" (гарантированный минус)
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

        // TP/SL пока считаем от тик-цены, но после исполнения пересчитаем от executedPrice
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
            log.info("[TRADE] COLLECT ENTRY {} plannedQty={} price={} quoteAmount={} tpPct={} slPct={} tp={} sl={} chatId={} ex={} net={} phase={}",
                    sym,
                    plannedQty.stripTrailingZeros().toPlainString(),
                    price.stripTrailingZeros().toPlainString(),
                    quoteAmount.stripTrailingZeros().toPlainString(),
                    tpPct.stripTrailingZeros().toPlainString(),
                    slPct.stripTrailingZeros().toPlainString(),
                    tp.stripTrailingZeros().toPlainString(),
                    sl.stripTrailingZeros().toPlainString(),
                    chatId, ex, net, phase
            );
            return EntryResult.ok(false, "BUY", plannedQty.stripTrailingZeros(), price, tp, sl, null);
        }

        try {
            Order order = orderService.placeMarket(ctx, OrderSide.BUY, quoteAmount, price);
            Long orderId = (order != null ? order.getId() : null);

            BigDecimal executedQty = pickExecutedQty(order, plannedQty);
            BigDecimal executedPrice = pickExecutedPrice(order, price);

            BigDecimal tpExec = calcTp(executedPrice, tpPct);
            BigDecimal slExec = calcSl(executedPrice, slPct);

            failCooldown.clear(key);

            // ✅ ЕДИНСТВЕННЫЙ ИСТОЧНИК ПРАВДЫ: TradeExecution сохраняет snapshot позиции
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

            log.info("[TRADE] ENTRY SPOT BUY {} executedQty={} plannedQty={} entryPrice={} tickPrice={} quoteAmount={} tpPct={} slPct={} tp={} sl={} chatId={} ex={} net={} phase={}",
                    sym,
                    executedQty.stripTrailingZeros().toPlainString(),
                    plannedQty.stripTrailingZeros().toPlainString(),
                    executedPrice.stripTrailingZeros().toPlainString(),
                    price.stripTrailingZeros().toPlainString(),
                    quoteAmount.stripTrailingZeros().toPlainString(),
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

        String sym = normalizeSymbol(symbol);
        if (sym == null) return ExitResult.fail("symbol пустой");

        if (price == null || price.signum() <= 0) return ExitResult.fail("price invalid");
        if (!isLong) return ExitResult.fail("spot_short_forbidden");

        String ex = safeExchange(exchange);
        NetworkType net = network;
        if (ex == null) return ExitResult.fail("exchange=null");
        if (net == null) return ExitResult.fail("network=null");

        // =====================================================
        // ✅ FIX: берём tp/sl/qty из PositionStore (если есть)
        // =====================================================
        BigDecimal effQty = entryQty;
        BigDecimal effTp  = tp;
        BigDecimal effSl  = sl;

        try {
            Optional<PositionStore.PositionSnapshot> posOpt =
                    positionStore.getPosition(chatId, strategyType, ex, net, sym);

            if (posOpt.isPresent()) {
                PositionStore.PositionSnapshot p = posOpt.get();

                if (p.qty() != null && p.qty().signum() > 0) effQty = p.qty();
                if (p.tp() != null && p.tp().signum() > 0) effTp = p.tp();
                if (p.sl() != null && p.sl().signum() > 0) effSl = p.sl();
            }
        } catch (Exception ignored) {
            // если PositionStore не умеет снапшоты — просто работаем по параметрам метода
        }

        if (effQty == null || effQty.signum() <= 0) return ExitResult.fail("entryQty invalid");
        if (effTp == null || effSl == null) return ExitResult.fail("tp/sl null");

        boolean tpHit = price.compareTo(effTp) >= 0;
        boolean slHit = price.compareTo(effSl) <= 0;
        if (!tpHit && !slHit) return ExitResult.fail("not_hit");

        OrderService.OrderContext ctx = new OrderService.OrderContext(
                chatId,
                strategyType,
                sym,
                null,
                null,
                "EXIT",
                ex,
                net
        );

        try {
            Order order = orderService.placeMarket(ctx, OrderSide.SELL, effQty, price);

            BigDecimal executedExitPrice = pickExecutedPrice(order, price);

            safeLive(() -> live.clearTpSl(chatId, strategyType, sym));
            safeLive(() -> live.clearPriceLines(chatId, strategyType, sym));
            safeLive(() -> live.pushSignal(chatId, strategyType, sym, null,
                    Signal.sell(executedExitPrice.doubleValue(), tpHit ? "TP" : "SL")));

            log.info("[TRADE] EXIT SPOT SELL {} qty={} exitPrice={} tickPrice={} tpHit={} slHit={} chatId={} ex={} net={}",
                    sym,
                    effQty.stripTrailingZeros().toPlainString(),
                    executedExitPrice.stripTrailingZeros().toPlainString(),
                    price.stripTrailingZeros().toPlainString(),
                    tpHit,
                    slHit,
                    chatId,
                    ex,
                    net
            );

            // ✅ чистим позицию полностью (факт + snapshot)
            positionStore.clearPosition(chatId, strategyType, ex, net, sym);

            // =====================================================
            // ✅ AUTO-TUNE (reflection-safe)
            // =====================================================
            boolean allowTune = allowAutoTune(chatId, strategyType, ex, net);

            if (allowTune) {
                mlAutoTuneRuntime.triggerTuneDebounced(
                        chatId,
                        strategyType,
                        ex,
                        net,
                        "after-close",
                        Duration.ofSeconds(10)
                );
            } else {
                log.debug("[TRADE] AUTOTUNE SKIP chatId={} type={} ex={} net={} (autoTuneEnabled=false или phase блокирует)",
                        chatId, strategyType, ex, net);
            }

            return ExitResult.ok(tpHit, slHit, executedExitPrice, BigDecimal.ZERO);

        } catch (Exception e) {
            String code = mapTradeErrorCode(e);
            log.error("[TRADE] EXIT FAILED {} chatId={} code={} err={}", sym, chatId, code, e.toString(), e);
            return ExitResult.fail(code);
        }
    }

    // ================= helpers =================

    private void safeLive(Runnable r) {
        try { r.run(); } catch (Exception ignored) {}
    }

    /**
     * ✅ Reflection-safe проверка авто-тюнинга:
     * - не зависит от конкретного набора методов в StrategySettingsService
     * - НЕ ломает компиляцию, если у тебя нет getSettings(...)
     */
    private boolean allowAutoTune(Long chatId, StrategyType type, String ex, NetworkType net) {
        try {
            StrategySettings s = tryLoadSettings(chatId, type, ex, net);
            if (s == null) return false;

            String phase = normalizeUpperNullable(s.getRunPhase());
            boolean phaseBlocks = PHASE_BACKTEST.equals(phase) || PHASE_COLLECT.equals(phase);

            return s.isAutoTuneEnabled() && !phaseBlocks;
        } catch (Exception ignored) {
            return false;
        }
    }

    private StrategySettings tryLoadSettings(Long chatId, StrategyType type, String ex, NetworkType net) {
        if (settingsService == null) return null;
        try {
            // 1) getSettings(chatId,type,ex,net)
            try {
                Method m = settingsService.getClass().getMethod(
                        "getSettings", Long.class, StrategyType.class, String.class, NetworkType.class
                );
                Object r = m.invoke(settingsService, chatId, type, ex, net);
                if (r instanceof StrategySettings ss) return ss;
            } catch (NoSuchMethodException ignored) {}

            // 2) getOrCreate(chatId,type,ex,net)
            try {
                Method m = settingsService.getClass().getMethod(
                        "getOrCreate", Long.class, StrategyType.class, String.class, NetworkType.class
                );
                Object r = m.invoke(settingsService, chatId, type, ex, net);
                if (r instanceof StrategySettings ss) return ss;
            } catch (NoSuchMethodException ignored) {}

            // 3) getSettingsOrThrow(chatId,type,ex,net)
            try {
                Method m = settingsService.getClass().getMethod(
                        "getSettingsOrThrow", Long.class, StrategyType.class, String.class, NetworkType.class
                );
                Object r = m.invoke(settingsService, chatId, type, ex, net);
                if (r instanceof StrategySettings ss) return ss;
            } catch (NoSuchMethodException ignored) {}

            // 4) getOrCreate(chatId,type)
            try {
                Method m = settingsService.getClass().getMethod(
                        "getOrCreate", Long.class, StrategyType.class
                );
                Object r = m.invoke(settingsService, chatId, type);
                if (r instanceof StrategySettings ss) return ss;
            } catch (NoSuchMethodException ignored) {}

        } catch (Exception ignored) {}

        return null;
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

    private BigDecimal calcTp(BigDecimal entryPrice, BigDecimal tpPct) {
        BigDecimal k = tpPct.divide(BigDecimal.valueOf(100), 12, RoundingMode.HALF_UP);
        return entryPrice.multiply(BigDecimal.ONE.add(k)).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal calcSl(BigDecimal entryPrice, BigDecimal slPct) {
        BigDecimal k = slPct.divide(BigDecimal.valueOf(100), 12, RoundingMode.HALF_UP);
        return entryPrice.multiply(BigDecimal.ONE.subtract(k)).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveQuoteAmount(Long chatId, StrategyType strategyType, StrategySettings ss) {

        BigDecimal riskPct = ss.getRiskPerTradePct();
        if (riskPct == null || riskPct.signum() <= 0) return BigDecimal.ZERO;

        BigDecimal available = null;

        AccountBalanceSnapshot snap = accountBalanceService.getSnapshot(
                chatId, strategyType, ss.getExchangeName(), ss.getNetworkType()
        );

        if (snap != null && snap.isConnectionOk()) {
            BigDecimal free = snap.getSelectedFreeBalance();
            if (free != null && free.signum() > 0) available = free;
        }

        if (available == null) {
            BigDecimal budget = ss.getMaxExposureUsd();
            if (budget == null || budget.signum() <= 0) return BigDecimal.ZERO;
            available = budget;
        }

        BigDecimal limited = applyMaxExposureLimits(available, ss);

        return limited
                .multiply(riskPct)
                .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
    }

    private BigDecimal applyMaxExposureLimits(BigDecimal available, StrategySettings ss) {
        if (available == null || available.signum() <= 0) return BigDecimal.ZERO;

        BigDecimal maxUsd = ss.getMaxExposureUsd();
        if (maxUsd != null && maxUsd.signum() > 0) return available.min(maxUsd);

        BigDecimal pct = ss.getMaxExposurePct();
        if (pct != null && pct.signum() > 0 && pct.compareTo(BigDecimal.valueOf(100)) <= 0) {
            BigDecimal byPct = available
                    .multiply(pct)
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
            return available.min(byPct);
        }

        return available;
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

    // =====================================================
    // ✅ EXECUTION DATA HELPERS (не завязаны на поля Order)
    // =====================================================

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
}
