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
        if (signal == null || ctx == null) return;

        StrategyRuntimeState state = ctx.getState();
        if (state == null) return;

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

        BigDecimal price = safePrice(ctx.getPrice());
        if (price == null) {
            log.debug("⛔ BUY skipped — price is null/invalid");
            return;
        }

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

        // TP / SL как линии (если заданы)
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
        } else {
            // если вдруг зона не задана — гарантированно чистим
            live.clearWindowZone(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol());
        }

        log.info("🟢 BUY executed @ {} | {}", price, safeReason(signal));
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

        BigDecimal price = safePrice(ctx.getPrice());
        if (price == null) {
            log.debug("⛔ SELL skipped — price is null/invalid");
            return;
        }

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
        } else {
            live.clearWindowZone(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol());
        }

        log.info("🔴 SELL executed @ {} | {}", price, safeReason(signal));
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

        BigDecimal price = safePrice(ctx.getPrice());
        if (price == null) {
            // даже если цены нет — позицию закрываем по состоянию,
            // а UI чистим
            state.closePosition();
            clearUi(ctx);
            log.info("🚪 EXIT position (no price) | {}", safeReason(signal));
            return;
        }

        state.closePosition();

        // ===== EXIT MARKER =====
        // Важно: LayerRenderer принимает маркеры BUY/SELL,
        // поэтому EXIT обычно лучше рисовать как SELL (для long) или BUY (для short).
        // Но раз ты хочешь "EXIT" — оставляем, только учитывай JS-валидацию.
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
        clearUi(ctx);

        log.info("🚪 EXIT position | {}", safeReason(signal));
    }

    // =====================================================
    // HELPERS
    // =====================================================
    private void clearUi(StrategyContext ctx) {
        // 1) убираем entry/tp/sl линии (JS должен уметь очищать по payload=null)
        live.clearPriceLines(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol());

        // 2) tp/sl (legacy слой) — только через clear (иначе null-null ломает компиляцию в других местах)
        live.clearTpSl(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol());

        // 3) window zone — только через clear
        live.clearWindowZone(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol());
    }

    private BigDecimal safePrice(BigDecimal price) {
        if (price == null) return null;
        if (price.signum() <= 0) return null;
        return price;
    }

    private String safeReason(Signal signal) {
        try {
            return signal.getReason() != null ? signal.getReason() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
