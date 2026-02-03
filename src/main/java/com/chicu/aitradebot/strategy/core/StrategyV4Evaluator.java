package com.chicu.aitradebot.strategy.core;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.strategy.core.context.RuntimeStrategyContext;
import com.chicu.aitradebot.strategy.core.context.StrategyContext;
import com.chicu.aitradebot.strategy.core.runtime.StrategyRuntimeState;
import com.chicu.aitradebot.strategy.registry.StrategyRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * V4-LIVE StrategyContext dispatcher
 *
 * ✅ Подготавливает контекст и вызывает live-стратегию
 * (точка, куда дальше аккуратно подключим Collect / Gate / AutoTune)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyV4Evaluator {

    private final StrategyRegistry strategyRegistry;

    /**
     * Backward-compatible вызов (если где-то ещё не прокинут tradeTsMs).
     */
    public void dispatch(
            Long chatId,
            StrategyType type,
            String symbol,
            String exchange,
            StrategyRuntimeState state,
            Object settings,
            double[] closes,
            BigDecimal price,
            NetworkType networkType
    ) {
        long ts = System.currentTimeMillis();
        dispatch(chatId, type, symbol, exchange, state, settings, closes, price, networkType, ts);
    }

    /**
     * Основной вызов: с временем тика (tradeTsMs).
     */
    public void dispatch(
            Long chatId,
            StrategyType type,
            String symbol,
            String exchange,
            StrategyRuntimeState state,
            Object settings,
            double[] closes,
            BigDecimal price,
            NetworkType networkType,
            long tradeTsMs
    ) {
        if (chatId == null || type == null || symbol == null || price == null) {
            return;
        }

        // =====================================================
        // Контекст (единая структура для пайплайна)
        // =====================================================
        StrategyContext ctx = RuntimeStrategyContext.builder()
                .chatId(chatId)
                .strategyType(type)
                .symbol(symbol)
                .exchange(exchange)
                .networkType(networkType)
                .price(price)
                .closes(closes != null ? closes : new double[0])
                .settings(settings)
                .state(state)
                .build();

        // =====================================================
        // V4-LIVE: единый вход в стратегию
        // =====================================================
        TradingStrategy strategy = strategyRegistry.get(type);
        if (strategy == null || !strategy.isActive(chatId)) {
            return;
        }

        // Время тика важно для Collect/лейблинга/метрик
        Instant tickTime = Instant.ofEpochMilli(tradeTsMs);

        // Пока стратегии работают через onPriceUpdate; дальше здесь появится gateway-пайплайн:
        // Collect -> Gate -> Strategy
        strategy.onPriceUpdate(chatId, symbol, price, tickTime);
    }
}
