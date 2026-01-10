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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeExecutionServiceImpl implements TradeExecutionService {

    private static final int QTY_SCALE = 8;

    private final OrderService orderService;
    private final StrategyLivePublisher live;
    private final AccountBalanceService accountBalanceService;

    @Override
    public EntryResult executeEntry(Long chatId,
                                    StrategyType strategyType,
                                    String symbol,
                                    BigDecimal price,
                                    BigDecimal diffPct,
                                    Instant time,
                                    StrategySettings ss) {

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

        // ❗ TP/SL здесь НЕ вычисляем: они больше не в StrategySettings.
        // Их должен отдавать слой стратегии/параметров (из таблицы конкретной стратегии).
        BigDecimal tp = null;
        BigDecimal sl = null;

        // сигнал в UI
        live.pushSignal(chatId, strategyType, symbol, null, Signal.buy(price.doubleValue(), "entry"));

        Order order = orderService.placeMarket(
                chatId,
                symbol,
                "BUY",
                qty,
                price,
                strategyType.name()
        );

        Long orderId = order != null ? order.getId() : null;

        log.info("[TRADE] ENTRY SPOT BUY {} qty={} price={} quoteAmount={} chatId={}",
                symbol, qty, price, quoteAmount, chatId);

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
        live.clearTpSl(chatId, strategyType, symbol);
        live.clearPriceLines(chatId, strategyType, symbol);
        live.pushSignal(chatId, strategyType, symbol, null,
                Signal.sell(price.doubleValue(), tpHit ? "TP" : "SL"));

        log.info("[TRADE] EXIT SPOT SELL {} qty={} price={} tpHit={} slHit={} chatId={}",
                symbol, entryQty, price, tpHit, slHit, chatId);

        return ExitResult.ok(tpHit, slHit, price, BigDecimal.ZERO);
    }

    // =====================================================
    // helpers
    // =====================================================

    /**
     * Сумма входа в QUOTE.
     * Источник денег:
     * - если есть соединение с биржей -> используем free выбранного asset
     * - если соединения нет -> используем maxExposureUsd как бюджет (из StrategySettings)
     *
     * Затем:
     * - применяем ограничения maxExposureUsd / maxExposurePct
     * - применяем риск riskPerTradePct
     */
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

        // ✅ FIX: maxExposurePct теперь BigDecimal (а не Integer)
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
