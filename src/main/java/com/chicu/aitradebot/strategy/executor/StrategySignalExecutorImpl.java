package com.chicu.aitradebot.strategy.executor;

import com.chicu.aitradebot.strategy.core.context.StrategyContext;
import com.chicu.aitradebot.strategy.core.runtime.StrategyRuntimeState;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategySignalExecutorImpl implements StrategySignalExecutor {

    private final StrategyLivePublisher live;

    @Override
    public void execute(Signal signal, StrategyContext ctx) {

        StrategyRuntimeState state = ctx.getState();

        switch (signal.getType()) {
            case BUY -> handleBuy(signal, ctx, state);
            case SELL -> handleSell(signal, ctx, state);
            case EXIT -> handleExit(signal, ctx, state);
            case HOLD -> {
                // ничего
            }
        }
    }

    // =====================================================
    // BUY
    // =====================================================
    private void handleBuy(Signal signal,
                           StrategyContext ctx,
                           StrategyRuntimeState state) {

        if (state.hasOpenPosition()) {
            log.debug("⛔ BUY skipped — position already open");
            return;
        }

        BigDecimal price = ctx.getPrice();

        state.setEntryPrice(price);
        state.openPosition();

        // ===== TRADE MARKER =====
        live.pushTrade(
                ctx.getChatId(),
                ctx.getStrategyType(),
                ctx.getSymbol(),
                "BUY",
                price,
                BigDecimal.ONE,
                Instant.now()
        );

        // ===== PRICE LINES =====
        live.pushPriceLine(
                ctx.getChatId(),
                ctx.getStrategyType(),
                ctx.getSymbol(),
                "ENTRY",
                price
        );

        if (state.getTakeProfit() != null) {
            live.pushPriceLine(
                    ctx.getChatId(),
                    ctx.getStrategyType(),
                    ctx.getSymbol(),
                    "TP",
                    state.getTakeProfit()
            );
        }

        if (state.getStopLoss() != null) {
            live.pushPriceLine(
                    ctx.getChatId(),
                    ctx.getStrategyType(),
                    ctx.getSymbol(),
                    "SL",
                    state.getStopLoss()
            );
        }

        // ===== WINDOW ZONE (SCALPING) =====
        if (state.getWindowHigh() != null && state.getWindowLow() != null) {
            live.pushWindowZone(
                    ctx.getChatId(),
                    ctx.getStrategyType(),
                    ctx.getSymbol(),
                    state.getWindowHigh(),
                    state.getWindowLow()
            );
        }

        log.info("🟢 BUY executed @ {} | {}", price, signal.getReason());
    }

    // =====================================================
    // SELL
    // =====================================================
    private void handleSell(Signal signal,
                            StrategyContext ctx,
                            StrategyRuntimeState state) {

        if (state.hasOpenPosition()) {
            log.debug("⛔ SELL skipped — position already open");
            return;
        }

        BigDecimal price = ctx.getPrice();

        state.setEntryPrice(price);
        state.openPosition();

        live.pushTrade(
                ctx.getChatId(),
                ctx.getStrategyType(),
                ctx.getSymbol(),
                "SELL",
                price,
                BigDecimal.ONE,
                Instant.now()
        );

        live.pushPriceLine(
                ctx.getChatId(),
                ctx.getStrategyType(),
                ctx.getSymbol(),
                "ENTRY",
                price
        );

        if (state.getTakeProfit() != null) {
            live.pushPriceLine(
                    ctx.getChatId(),
                    ctx.getStrategyType(),
                    ctx.getSymbol(),
                    "TP",
                    state.getTakeProfit()
            );
        }

        if (state.getStopLoss() != null) {
            live.pushPriceLine(
                    ctx.getChatId(),
                    ctx.getStrategyType(),
                    ctx.getSymbol(),
                    "SL",
                    state.getStopLoss()
            );
        }

        if (state.getWindowHigh() != null && state.getWindowLow() != null) {
            live.pushWindowZone(
                    ctx.getChatId(),
                    ctx.getStrategyType(),
                    ctx.getSymbol(),
                    state.getWindowHigh(),
                    state.getWindowLow()
            );
        }

        log.info("🔴 SELL executed @ {} | {}", price, signal.getReason());
    }

    // =====================================================
    // EXIT
    // =====================================================
    private void handleExit(Signal signal,
                            StrategyContext ctx,
                            StrategyRuntimeState state) {

        if (!state.hasOpenPosition()) {
            return;
        }

        BigDecimal price = ctx.getPrice();

        state.closePosition();

        // ===== EXIT MARKER =====
        live.pushTrade(
                ctx.getChatId(),
                ctx.getStrategyType(),
                ctx.getSymbol(),
                "EXIT",
                price,
                BigDecimal.ONE,
                Instant.now()
        );

        // ===== CLEAR VISUALS =====
        live.pushPriceLine(
                ctx.getChatId(),
                ctx.getStrategyType(),
                ctx.getSymbol(),
                "ENTRY",
                null
        );

        live.pushTpSl(
                ctx.getChatId(),
                ctx.getStrategyType(),
                ctx.getSymbol(),
                null,
                null
        );

        live.pushWindowZone(
                ctx.getChatId(),
                ctx.getStrategyType(),
                ctx.getSymbol(),
                null,
                null
        );

        log.info("🚪 EXIT position | {}", signal.getReason());
    }
}
