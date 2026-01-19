package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.account.AccountBalanceService;
import com.chicu.aitradebot.account.AccountBalanceSnapshot;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeExecutionServiceImpl implements TradeExecutionService {

    private static final int QTY_SCALE = 8;
    private static final int PRICE_SCALE = 8;

    private final OrderService orderService;
    private final StrategyLivePublisher live;
    private final AccountBalanceService accountBalanceService;

    /**
     * ✅ BACKWARD COMPAT:
     * Старые стратегии пока могут звать этот метод.
     * Мы попробуем вытащить tp/sl через рефлексию из StrategySettings (если они ещё там есть),
     * иначе вернём понятную ошибку.
     */
    @Override
    public EntryResult executeEntry(Long chatId,
                                    StrategyType strategyType,
                                    String symbol,
                                    BigDecimal price,
                                    BigDecimal diffPct,
                                    Instant time,
                                    StrategySettings ss) {

        BigDecimal tpPct = readBigDecimal(ss, "getTakeProfitPct");
        BigDecimal slPct = readBigDecimal(ss, "getStopLossPct");
        return executeEntry(chatId, strategyType, symbol, price, diffPct, time, ss, tpPct, slPct);
    }

    /**
     * ✅ НОВЫЙ КОНТРАКТ:
     * tpPct/slPct приходят из настроек КОНКРЕТНОЙ стратегии
     * (например WindowScalpingStrategySettings / ScalpingStrategySettings и т.д.)
     */
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
        if (symbol == null || symbol.isBlank()) return EntryResult.fail("symbol пустой");
        if (price == null || price.signum() <= 0) return EntryResult.fail("price invalid");

        // ✅ SPOT: только LONG. Если сигнал не на рост — не входим.
        if (diffPct == null || diffPct.signum() <= 0) {
            return EntryResult.fail("SPOT: entry только BUY (diff<=0)");
        }

        // контекст обязателен (иначе баланс/лимиты не проверить корректно)
        if (ss.getExchangeName() == null || ss.getExchangeName().isBlank()) {
            return EntryResult.fail("exchangeName пустой в StrategySettings");
        }
        if (ss.getNetworkType() == null) {
            return EntryResult.fail("networkType пустой в StrategySettings");
        }

        // ✅ TP/SL проценты — ТЕПЕРЬ из стратегии
        if (!isValidPct(tpPct)) return EntryResult.fail("takeProfitPct invalid (нужно в настройках стратегии)");
        if (!isValidPct(slPct)) return EntryResult.fail("stopLossPct invalid (нужно в настройках стратегии)");

        // сумма входа в QUOTE (USDT/USDC/...)
        BigDecimal quoteAmount = resolveQuoteAmount(chatId, strategyType, ss);
        if (quoteAmount == null || quoteAmount.signum() <= 0) {
            return EntryResult.fail("недостаточно средств/лимит бюджета/риск=0");
        }

        // qty BASE = quoteAmount / price
        BigDecimal qty = quoteAmount
                .divide(price, QTY_SCALE, RoundingMode.DOWN)
                .stripTrailingZeros();

        if (qty.signum() <= 0) {
            return EntryResult.fail("qty=0: мало средств или слишком высокая цена");
        }

        // ✅ Считаем абсолютные TP/SL от цены входа
        BigDecimal tp = calcTp(price, tpPct);
        BigDecimal sl = calcSl(price, slPct);

        if (tp.compareTo(price) <= 0) return EntryResult.fail("TP <= entryPrice (check takeProfitPct)");
        if (sl.compareTo(price) >= 0) return EntryResult.fail("SL >= entryPrice (check stopLossPct)");
        if (sl.signum() <= 0) return EntryResult.fail("SL <= 0 (check stopLossPct)");

        // UI: сигнал входа
        safeLive(() -> live.pushSignal(chatId, strategyType, symbol, null, Signal.buy(price.doubleValue(), "entry")));

        Order order = orderService.placeMarket(
                chatId,
                symbol,
                "BUY",
                qty,
                price,
                strategyType.name()
        );

        Long orderId = order != null ? order.getId() : null;

        log.info("[TRADE] ENTRY SPOT BUY {} qty={} price={} quoteAmount={} tpPct={} slPct={} tp={} sl={} chatId={}",
                symbol, qty, price, quoteAmount, tpPct, slPct, tp, sl, chatId);

        return EntryResult.ok(true, "BUY", qty, price, tp, sl, orderId);
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
                                       BigDecimal sl) {

        if (chatId == null) return ExitResult.fail("chatId=null");
        if (strategyType == null) return ExitResult.fail("strategyType=null");
        if (symbol == null || symbol.isBlank()) return ExitResult.fail("symbol пустой");
        if (price == null || price.signum() <= 0) return ExitResult.fail("price invalid");
        if (entryQty == null || entryQty.signum() <= 0) return ExitResult.fail("entryQty invalid");
        if (tp == null || sl == null) return ExitResult.fail("tp/sl null");

        // ✅ SPOT: у нас всегда long
        if (!isLong) return ExitResult.fail("SPOT: short запрещён");

        boolean tpHit = price.compareTo(tp) >= 0;
        boolean slHit = price.compareTo(sl) <= 0;

        if (!tpHit && !slHit) return ExitResult.fail("not hit");

        orderService.placeMarket(
                chatId,
                symbol,
                "SELL",
                entryQty,
                price,
                strategyType.name()
        );

        // UI
        safeLive(() -> live.clearTpSl(chatId, strategyType, symbol));
        safeLive(() -> live.clearPriceLines(chatId, strategyType, symbol));
        safeLive(() -> live.pushSignal(chatId, strategyType, symbol, null,
                Signal.sell(price.doubleValue(), tpHit ? "TP" : "SL")));

        log.info("[TRADE] EXIT SPOT SELL {} qty={} price={} tpHit={} slHit={} chatId={}",
                symbol, entryQty, price, tpHit, slHit, chatId);

        return ExitResult.ok(tpHit, slHit, price, BigDecimal.ZERO);
    }

    // =====================================================
    // helpers
    // =====================================================

    private void safeLive(Runnable r) {
        try { r.run(); } catch (Exception ignored) {}
    }

    private static boolean isValidPct(BigDecimal pct) {
        if (pct == null) return false;
        if (pct.signum() <= 0) return false;
        // в процентах: 0 < pct < 100
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

    private BigDecimal readBigDecimal(Object target, String getter) {
        if (target == null || getter == null) return null;
        try {
            Method m = target.getClass().getMethod(getter);
            Object v = m.invoke(target);
            if (v instanceof BigDecimal bd) return bd;
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private BigDecimal resolveQuoteAmount(Long chatId, StrategyType strategyType, StrategySettings ss) {

        BigDecimal riskPct = ss.getRiskPerTradePct();
        if (riskPct == null || riskPct.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal available = null;

        AccountBalanceSnapshot snap = accountBalanceService.getSnapshot(
                chatId, strategyType, ss.getExchangeName(), ss.getNetworkType()
        );

        if (snap != null && snap.isConnectionOk()) {
            BigDecimal free = snap.getSelectedFreeBalance();
            if (free != null && free.signum() > 0) {
                available = free;
            }
        }

        // offline/paper/ML: бюджета из maxExposureUsd
        if (available == null || available.signum() <= 0) {
            BigDecimal budget = ss.getMaxExposureUsd();
            if (budget == null || budget.signum() <= 0) return BigDecimal.ZERO;
            available = budget;
        }

        BigDecimal budget = applyMaxExposureLimits(available, ss);

        return budget
                .multiply(riskPct)
                .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
    }

    private BigDecimal applyMaxExposureLimits(BigDecimal available, StrategySettings ss) {
        if (available == null || available.signum() <= 0) return BigDecimal.ZERO;

        BigDecimal maxUsd = ss.getMaxExposureUsd();
        if (maxUsd != null && maxUsd.signum() > 0) {
            return available.min(maxUsd);
        }

        BigDecimal pct = ss.getMaxExposurePct();
        if (pct != null && pct.signum() > 0 && pct.compareTo(BigDecimal.valueOf(100)) <= 0) {
            BigDecimal byPct = available
                    .multiply(pct)
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
            return available.min(byPct);
        }

        return available;
    }
}
