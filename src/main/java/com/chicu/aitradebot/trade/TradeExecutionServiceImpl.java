package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.account.AccountBalanceService;
import com.chicu.aitradebot.account.AccountBalanceSnapshot;
import com.chicu.aitradebot.ai.runtime.MlAutoTuneRuntime;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeExecutionServiceImpl implements TradeExecutionService {

    private static final int QTY_SCALE = 8;
    private static final int PRICE_SCALE = 8;

    private static final String PHASE_COLLECT  = "COLLECT";
    private static final String PHASE_BACKTEST = "BACKTEST";
    private static final String PHASE_PAPER    = "PAPER";

    private final OrderService orderService;
    private final StrategyLivePublisher live;
    private final AccountBalanceService accountBalanceService;

    // ✅ настройки стратегии (для autotune по флагу)
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
                                    StrategySettings strategySettings) {
        return EntryResult.fail("TP/SL должны приходить из настроек конкретной стратегии. Используй executeEntry(..., tpPct, slPct).");
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
                    safeLive(() -> live.pushSignal(chatId, strategyType, sym, null,
                            Signal.buy(price.doubleValue(), "ml_gate_reject")));
                    return EntryResult.fail("ml_gate_reject");
                }
            }
        }

        if (positionStore.isInPosition(chatId, strategyType, ex, net)) {
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

        // Плановый qty (только для UI/логов/collectMode). Реальный qty вернёт OrderService после AI-GUARD.
        BigDecimal plannedQty = quoteAmount.divide(price, QTY_SCALE, RoundingMode.DOWN);
        if (plannedQty.signum() <= 0) return EntryResult.fail("qty=0");

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
            // ✅ ВАЖНО: BUY -> передаём quoteAmount (USDT), а не qty
            Order order = orderService.placeMarket(ctx, "BUY", quoteAmount, price);
            Long orderId = (order != null ? order.getId() : null);

            BigDecimal executedQty = (order != null && order.getQuantity() != null && order.getQuantity().signum() > 0)
                    ? order.getQuantity()
                    : plannedQty;

            failCooldown.clear(key);
            positionStore.markOpened(chatId, strategyType, ex, net, sym);

            log.info("[TRADE] ENTRY SPOT BUY {} executedQty={} plannedQty={} price={} quoteAmount={} tpPct={} slPct={} tp={} sl={} chatId={} ex={} net={} phase={}",
                    sym,
                    executedQty.stripTrailingZeros().toPlainString(),
                    plannedQty.stripTrailingZeros().toPlainString(),
                    price.stripTrailingZeros().toPlainString(),
                    quoteAmount.stripTrailingZeros().toPlainString(),
                    tpPct.stripTrailingZeros().toPlainString(),
                    slPct.stripTrailingZeros().toPlainString(),
                    tp.stripTrailingZeros().toPlainString(),
                    sl.stripTrailingZeros().toPlainString(),
                    chatId, ex, net, phase
            );

            return EntryResult.ok(true, "BUY", executedQty.stripTrailingZeros(), price, tp, sl, orderId);

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
        if (entryQty == null || entryQty.signum() <= 0) return ExitResult.fail("entryQty invalid");
        if (tp == null || sl == null) return ExitResult.fail("tp/sl null");
        if (!isLong) return ExitResult.fail("spot_short_forbidden");

        String ex = safeExchange(exchange);
        NetworkType net = network;
        if (ex == null) return ExitResult.fail("exchange=null");
        if (net == null) return ExitResult.fail("network=null");

        boolean tpHit = price.compareTo(tp) >= 0;
        boolean slHit = price.compareTo(sl) <= 0;
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
            // SELL по SPOT — это baseQty (как было)
            orderService.placeMarket(ctx, "SELL", entryQty, price);

            safeLive(() -> live.clearTpSl(chatId, strategyType, sym));
            safeLive(() -> live.clearPriceLines(chatId, strategyType, sym));
            safeLive(() -> live.pushSignal(chatId, strategyType, sym, null,
                    Signal.sell(price.doubleValue(), tpHit ? "TP" : "SL")));

            log.info("[TRADE] EXIT SPOT SELL {} qty={} price={} tpHit={} slHit={} chatId={} ex={} net={}",
                    sym,
                    entryQty.stripTrailingZeros().toPlainString(),
                    price.stripTrailingZeros().toPlainString(),
                    tpHit,
                    slHit,
                    chatId,
                    ex,
                    net
            );

            positionStore.markClosed(chatId, strategyType, ex, net, sym);

            // =====================================================
            // ✅ AUTO-TUNE: только если включено в StrategySettings
            // =====================================================
            boolean allowTune = false;
            try {
                StrategySettings s = settingsService.getSettings(chatId, strategyType, ex, net);
                if (s != null) {
                    String phase = normalizeUpperNullable(s.getRunPhase());
                    boolean phaseBlocks = PHASE_BACKTEST.equals(phase) || PHASE_COLLECT.equals(phase);
                    allowTune = s.isAutoTuneEnabled() && !phaseBlocks;
                }
            } catch (Exception ignored) {}

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

            return ExitResult.ok(tpHit, slHit, price, BigDecimal.ZERO);

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
}
