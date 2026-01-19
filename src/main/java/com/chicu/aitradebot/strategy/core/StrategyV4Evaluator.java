package com.chicu.aitradebot.strategy.core;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.strategy.core.context.RuntimeStrategyContext;
import com.chicu.aitradebot.strategy.core.context.StrategyContext;
import com.chicu.aitradebot.strategy.core.runtime.StrategyRuntimeState;
import com.chicu.aitradebot.strategy.scalping.ScalpingStrategySettings;
import com.chicu.aitradebot.strategy.scalping.ScalpingStrategySettingsService;
import com.chicu.aitradebot.strategy.registry.StrategyRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * V4-LIVE StrategyContext dispatcher
 *
 * ❌ НЕ evaluate
 * ❌ НЕ Signal
 * ❌ НЕ StrategyEngine
 * ✅ Подготавливает контекст и вызывает live-стратегию
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyV4Evaluator {

    private final StrategyRegistry strategyRegistry;
    private final ScalpingStrategySettingsService scalpingSettingsService;

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

        if (chatId == null || type == null || symbol == null || price == null) {
            return;
        }

        // =====================================================
        // 🔗 Подмена настроек под стратегию (как у тебя и было)
        // =====================================================
        Object effectiveSettings = settings;

        if (settings instanceof StrategySettings base) {

            if (base.getType() == StrategyType.SCALPING) {
                ScalpingStrategySettings scalping =
                        scalpingSettingsService.getOrCreate(chatId);
                effectiveSettings = scalping;

                log.debug("🧩 Using SCALPING settings for chatId={}", chatId);
            }

            // другие стратегии — сюда же
        }

        // =====================================================
        // Контекст (если нужен стратегии)
        // =====================================================
        StrategyContext ctx = RuntimeStrategyContext.builder()
                .chatId(chatId)
                .symbol(symbol)
                .exchange(exchange)
                .networkType(networkType)
                .price(price)
                .closes(closes != null ? closes : new double[0])
                .settings(effectiveSettings)
                .state(state)
                .build();

        // =====================================================
        // V4-LIVE: ЕДИНСТВЕННЫЙ ВХОД
        // =====================================================
        TradingStrategy strategy = strategyRegistry.get(type);
        if (strategy == null || !strategy.isActive(chatId)) {
            return;
        }

        strategy.onPriceUpdate(chatId, symbol, price, null);
    }
}
