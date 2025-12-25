package com.chicu.aitradebot.strategy.runner;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.market.MarketPriceService;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.registry.StrategyRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * V4-LIVE StrategyRunner
 *
 * ❌ НЕ использует StrategyEngine
 * ❌ НЕ использует Signal / evaluate
 * ✅ ТОЛЬКО диспатчит тик в активные стратегии
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyRunnerImpl implements StrategyRunner {

    private final StrategyRegistry strategyRegistry;
    private final StrategySettingsService settingsService;
    private final MarketPriceService priceService;

    @Override
    public void onTick(
            Long chatId,
            String symbol,
            String exchange,
            NetworkType networkType
    ) {

        if (chatId == null || chatId <= 0) return;
        if (symbol == null || symbol.isBlank()) return;
        if (exchange == null || networkType == null) return;

        // 1️⃣ Цена
        BigDecimal price = priceService.getLastPrice(symbol).orElse(null);
        if (price == null || price.signum() <= 0) return;

        // 2️⃣ Прогон по стратегиям
        for (StrategyType type : StrategyType.values()) {

            StrategySettings s;
            try {
                s = settingsService.getSettings(
                        chatId,
                        type,
                        exchange,
                        networkType
                );
            } catch (Exception e) {
                continue;
            }

            if (s == null) continue;
            if (!s.isActive()) continue;

            // 🔒 защита по инструменту
            if (s.getSymbol() == null ||
                !symbol.equalsIgnoreCase(s.getSymbol())) {
                continue;
            }

            TradingStrategy strategy = strategyRegistry.get(type);
            if (strategy == null) continue;

            // 🔒 защита по runtime-активности
            if (!strategy.isActive(chatId)) continue;

            // 3️⃣ ЕДИНЫЙ ВХОД В СТРАТЕГИЮ (V4)
            strategy.onPriceUpdate(
                    chatId,
                    symbol,
                    price,
                    null // стратегия сама подставит Instant.now()
            );
        }
    }
}
