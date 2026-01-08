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
        if (ss == null) return EntryResult.fail("StrategySettings=null");
        if (symbol == null || symbol.isBlank()) return EntryResult.fail("symbol пустой");
        if (price == null || price.signum() <= 0) return EntryResult.fail("price invalid");

        // ✅ SPOT: только LONG. Если сигнал не на рост — не входим.
        if (diffPct == null || diffPct.signum() <= 0) {
            return EntryResult.fail("SPOT: entry только BUY (diff<=0)");
        }

        String side = "BUY";

        // сумма входа в QUOTE (например USDT)
        BigDecimal quoteAmount = resolveQuoteAmount(chatId, strategyType, ss);
        if (quoteAmount == null || quoteAmount.signum() <= 0) {
            return EntryResult.fail("нет средств: проверь accountAsset/баланс или capitalUsd");
        }

        // qty BASE = quoteAmount / price
        BigDecimal qty = quoteAmount
                .divide(price, QTY_SCALE, RoundingMode.DOWN)
                .stripTrailingZeros();

        if (qty.signum() <= 0) {
            return EntryResult.fail("qty=0: мало средств или слишком высокая цена/малый риск");
        }

        // TP/SL (%)
        BigDecimal tpPct = nz(ss.getTakeProfitPct());
        BigDecimal slPct = nz(ss.getStopLossPct());

        BigDecimal tp = price.multiply(BigDecimal.ONE.add(tpPct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)));
        BigDecimal sl = price.multiply(BigDecimal.ONE.subtract(slPct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)));

        // сигнал в UI
        live.pushSignal(chatId, strategyType, symbol, null, Signal.buy(price.doubleValue(), "entry"));

        Order order = orderService.placeMarket(
                chatId,
                symbol,
                side,
                qty,
                price,
                strategyType.name()
        );

        Long orderId = order != null ? order.getId() : null;

        log.info("[TRADE] ENTRY SPOT BUY {} qty={} price={} tp={} sl={} quoteAmount={} (chatId={})",
                symbol, qty, price, tp, sl, quoteAmount, chatId);

        return EntryResult.ok(true, side, qty, price, tp, sl, orderId);
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

        if (price == null || price.signum() <= 0) return ExitResult.fail("price invalid");
        if (entryQty == null || entryQty.signum() <= 0) return ExitResult.fail("entryQty invalid");
        if (tp == null || sl == null) return ExitResult.fail("tp/sl null");

        // ✅ SPOT: у нас всегда long, но на всякий случай
        if (!isLong) {
            return ExitResult.fail("SPOT: short запрещён");
        }

        boolean tpHit = price.compareTo(tp) >= 0;
        boolean slHit = price.compareTo(sl) <= 0;

        if (!tpHit && !slHit) return ExitResult.fail("not hit");

        String exitSide = "SELL";

        orderService.placeMarket(
                chatId,
                symbol,
                exitSide,
                entryQty,
                price,
                strategyType.name()
        );

        BigDecimal pnlPct = tpHit ? BigDecimal.valueOf(0.1) : BigDecimal.valueOf(-0.1);

        live.clearTpSl(chatId, strategyType, symbol);
        live.clearPriceLines(chatId, strategyType, symbol);
        live.pushSignal(chatId, strategyType, symbol, null,
                Signal.sell(price.doubleValue(), tpHit ? "TP" : "SL"));

        log.info("[TRADE] EXIT SPOT SELL {} qty={} price={} tpHit={} slHit={} pnl~{}% (chatId={})",
                symbol, entryQty, price, tpHit, slHit, pnlPct, chatId);

        return ExitResult.ok(tpHit, slHit, price, pnlPct);
    }

    // =====================================================
    // helpers (SPOT)
    // =====================================================

    /**
     * Возвращает сумму в QUOTE (USDT/USDC/...) на вход.
     * Сейчас берём из выбранного баланса (accountAsset) или fallback на capitalUsd.
     */
    private BigDecimal resolveQuoteAmount(Long chatId, StrategyType strategyType, StrategySettings ss) {

        BigDecimal riskPct = nz(ss.getRiskPerTradePct());
        if (riskPct.signum() <= 0) riskPct = BigDecimal.ONE; // 1%

        // 1) пробуем баланс выбранного accountAsset
        AccountBalanceSnapshot snap = accountBalanceService.getSnapshot(
                chatId, strategyType, ss.getExchangeName(), ss.getNetworkType()
        );

        BigDecimal free = (snap != null) ? snap.getSelectedFreeBalance() : null;

        // 2) fallback на capitalUsd
        if (free == null || free.signum() <= 0) {
            BigDecimal capitalUsd = ss.getCapitalUsd();
            if (capitalUsd == null || capitalUsd.signum() <= 0) return BigDecimal.ZERO;

            return capitalUsd
                    .multiply(riskPct)
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        }

        return free
                .multiply(riskPct)
                .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
