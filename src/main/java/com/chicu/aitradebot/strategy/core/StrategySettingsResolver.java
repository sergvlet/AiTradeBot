package com.chicu.aitradebot.strategy.core;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.fibonacci.FibonacciGridStrategySettingsService;
import com.chicu.aitradebot.strategy.scalping.ScalpingStrategySettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StrategySettingsResolver {

    private final ScalpingStrategySettingsService scalpingService;
    private final FibonacciGridStrategySettingsService fibService;
    // + другие стратегии

    public Object resolve(Long chatId, StrategyType type) {

        return switch (type) {

            case SCALPING ->
                    scalpingService.getOrCreate(chatId);

            case FIBONACCI_GRID ->
                    fibService.getOrCreate(chatId);

            // SMART_FUSION, RSI_EMA и т.д.

            default ->
                    throw new IllegalStateException(
                        "No settings resolver for strategy " + type
                    );
        };
    }
}
