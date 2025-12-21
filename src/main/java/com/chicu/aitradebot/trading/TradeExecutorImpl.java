package com.chicu.aitradebot.trading;

import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import com.chicu.aitradebot.strategy.core.context.StrategyContext;
import com.chicu.aitradebot.strategy.core.runtime.StrategyRuntimeState;
import com.chicu.aitradebot.strategy.core.settings.OrderVolumeProvider;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.core.signal.SignalType;
import com.chicu.aitradebot.trading.position.ActivePosition;
import com.chicu.aitradebot.trading.position.PositionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class TradeExecutorImpl implements TradeExecutor {

    private final ExchangeClientFactory exchangeClientFactory;
    private final ExchangeSettingsService exchangeSettingsService;
    private final PositionManager positionManager;

    @Override
    public void execute(StrategyContext ctx, Signal signal) {

        StrategyRuntimeState state = ctx.getState();

        Long chatId = ctx.getChatId();
        String symbol = ctx.getSymbol();
        BigDecimal price = ctx.getPrice();

        SignalType type = signal.getType();

        // =========================================================
        // HOLD
        // =========================================================
        if (type == SignalType.HOLD) {
            return;
        }

        // =========================================================
        // EXIT
        // =========================================================
        if (type == SignalType.EXIT) {

            ActivePosition pos = positionManager
                    .get(chatId, symbol)
                    .orElse(null);

            if (pos == null) {
                log.warn("⚠ EXIT без позиции chatId={} symbol={}", chatId, symbol);
                return;
            }

            OrderSide exitSide =
                    pos.getSide() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;

            ExchangeSettings settings = exchangeSettingsService.getOrCreate(
                    chatId,
                    ctx.getExchange(),
                    ctx.getNetworkType()
            );

            ExchangeClient exchangeClient = exchangeClientFactory.get(
                    settings.getExchange(),
                    settings.getNetwork()
            );

            try {
                exchangeClient.placeMarketOrder(
                        symbol,
                        exitSide,
                        pos.getQuantity()
                );
            } catch (Exception e) {
                log.error("❌ EXIT failed {} {} qty={}",
                        exitSide, symbol, pos.getQuantity(), e);
                return;
            }

            positionManager.close(chatId, symbol);
            state.closePosition();

            log.info("🚪 EXIT {} {} qty={}",
                    exitSide, symbol, pos.getQuantity());
            return;
        }

        // =========================================================
        // ENTRY BLOCK
        // =========================================================
        if (state.hasOpenPosition()) {
            log.debug("⛔ Entry blocked: position already open");
            return;
        }

        // =========================================================
        // SETTINGS
        // =========================================================
        ExchangeSettings settings = exchangeSettingsService.getOrCreate(
                chatId,
                ctx.getExchange(),
                ctx.getNetworkType()
        );

        ExchangeClient exchangeClient = exchangeClientFactory.get(
                settings.getExchange(),
                settings.getNetwork()
        );

        OrderSide side = (type == SignalType.BUY)
                ? OrderSide.BUY
                : OrderSide.SELL;

        BigDecimal qty = resolveOrderQty(ctx);

        try {
            exchangeClient.placeMarketOrder(symbol, side, qty);
        } catch (Exception e) {
            log.error("❌ ENTRY failed {} {} qty={}",
                    side, symbol, qty, e);
            return;
        }

        ActivePosition position = ActivePosition.builder()
                .chatId(chatId)
                .symbol(symbol)
                .side(side)
                .entryPrice(price)
                .quantity(qty)
                .openedAt(Instant.now())
                .build();

        positionManager.open(position);
        state.openPosition();

        log.info("✅ OPEN {} {} qty={} @ {}", side, symbol, qty, price);
    }

    // =========================================================
    // QTY — ТОЛЬКО ЧЕРЕЗ КОНТРАКТ
    // =========================================================
    private BigDecimal resolveOrderQty(StrategyContext ctx) {

        Object settings = ctx.getSettings();

        if (!(settings instanceof OrderVolumeProvider provider)) {
            throw new IllegalStateException(
                    "❌ StrategySettings не реализует OrderVolumeProvider: " +
                    (settings == null ? "null" : settings.getClass().getSimpleName())
            );
        }

        BigDecimal qty = provider.getOrderVolume();

        if (qty == null || qty.signum() <= 0) {
            throw new IllegalStateException("❌ Некорректный orderVolume: " + qty);
        }

        return qty;
    }
}
