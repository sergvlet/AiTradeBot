package com.chicu.aitradebot.web.facade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;

import java.util.List;

public record StrategyUi(

        // === ИДЕНТИФИКАЦИЯ ===
        Long id,
        Long chatId,
        StrategyType type,
        String exchangeName,
        NetworkType networkType,

        // === СОСТОЯНИЕ (runtime) ===
        boolean active,

        // === БАЗОВЫЕ НАСТРОЙКИ (из StrategySettings) ===
        String symbol,
        String timeframe,

        // === UI ===
        String title,
        String description,

        // === РЕЖИМ УПРАВЛЕНИЯ ===
        AdvancedControlMode advancedControlMode
) {

    // ================================================================
    // 🔁 PUBLIC MAPPER
    // ================================================================
    public static List<StrategyUi> fromSettings(List<StrategySettings> settings) {
        return settings.stream()
                .map(StrategyUi::fromSettings)
                .toList();
    }

    public static StrategyUi fromSettings(StrategySettings s) {

        UiText ui = uiText(s.getType());

        return new StrategyUi(
                s.getId(),
                s.getChatId(),
                s.getType(),
                safe(s.getExchangeName(), "BINANCE"),
                s.getNetworkType() != null ? s.getNetworkType() : NetworkType.MAINNET,

                // ❗ active будет корректно переопределён facade'ом
                false,

                safe(s.getSymbol(), "—"),
                safe(s.getTimeframe(), "—"),

                ui.title,
                ui.description,

                s.getAdvancedControlMode() != null
                        ? s.getAdvancedControlMode()
                        : AdvancedControlMode.MANUAL
        );
    }

    // ================================================================
    // 🔁 RUNTIME UPDATE (ВАЖНО!)
    // ================================================================
    public StrategyUi withActive(boolean active) {
        return new StrategyUi(
                id,
                chatId,
                type,
                exchangeName,
                networkType,
                active,
                symbol,
                timeframe,
                title,
                description,
                advancedControlMode
        );
    }

    // ================================================================
    // 🧩 EMPTY — если записи нет в БД
    // ================================================================
    public static StrategyUi empty(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {

        UiText ui = uiText(type);

        return new StrategyUi(
                null,
                chatId,
                type,
                safe(exchange, "BINANCE"),
                network != null ? network : NetworkType.MAINNET,
                false,
                "—",
                "—",
                ui.title,
                "Стратегия ещё не настроена",
                AdvancedControlMode.MANUAL
        );
    }

    // ================================================================
    // 🧠 UI TEXT
    // ================================================================
    private static UiText uiText(StrategyType type) {
        return switch (type) {
            case SCALPING -> new UiText(
                    "Scalping",
                    "Быстрые сделки на малых движениях цены"
            );
            case FIBONACCI_GRID -> new UiText(
                    "Fibonacci Grid",
                    "Сетка ордеров по уровням Фибоначчи"
            );
            case RSI_EMA -> new UiText(
                    "RSI + EMA",
                    "Трендовая стратегия на RSI и EMA"
            );
            case ML_INVEST -> new UiText(
                    "ML Invest",
                    "Инвестиционная стратегия с машинным обучением"
            );
            case SMART_FUSION -> new UiText(
                    "Smart Fusion",
                    "Комбинированная AI-стратегия"
            );
            default -> new UiText(
                    type.name(),
                    "Стратегия без UI-описания"
            );
        };
    }

    // ================================================================
    // 🧰 HELPERS
    // ================================================================
    private static String safe(String v, String def) {
        return (v != null && !v.isBlank()) ? v : def;
    }

    private record UiText(String title, String description) {}
}
