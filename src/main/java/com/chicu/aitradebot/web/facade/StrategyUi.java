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
    // 🧠 UI TEXT (все StrategyType)
    // ================================================================
    private static UiText uiText(StrategyType type) {
        return switch (type) {

            // SYSTEM / META
            case GLOBAL -> new UiText(
                    "Global",
                    "Глобальные настройки и общая логика оркестратора (не торговая стратегия)"
            );

            // I) MOMENTUM / TREND
            case MOMENTUM -> new UiText(
                    "Momentum",
                    "Торгует по импульсу: ищет ускорение движения цены и пытается войти в продолжение"
            );
            case TREND_FOLLOWING -> new UiText(
                    "Trend Following",
                    "Следование за трендом: вход по подтверждённому направлению, выход при ослаблении"
            );
            case EMA_CROSSOVER -> new UiText(
                    "EMA Crossover",
                    "Сигналы по пересечению быстрых/медленных EMA: классика трендовых входов"
            );
            case TREND -> new UiText(
                    "Trend",
                    "Базовая трендовая стратегия: оценивает направление и силу движения"
            );

            // II) MEAN REVERSION / RSI
            case MEAN_REVERSION -> new UiText(
                    "Mean Reversion",
                    "Возврат к среднему: ищет отклонение цены и играет на откат к средним значениям"
            );
            case RSI_OBOS -> new UiText(
                    "RSI OB/OS",
                    "RSI перекуплен/перепродан: входы на экстремумах с расчётом на коррекцию"
            );

            // III) SCALPING
            case SCALPING -> new UiText(
                    "Scalping",
                    "Быстрые сделки на малых движениях цены с жёсткими ограничениями по риску"
            );
            case WINDOW_SCALPING -> new UiText(
                    "Window Scalping",
                    "Скальпинг по окну: анализ high/low за окно и вход при пробое/возврате внутри диапазона"
            );

            // IV) BREAKOUT
            case BREAKOUT -> new UiText(
                    "Breakout",
                    "Пробой уровня/диапазона: вход при выходе цены из консолидации"
            );
            case VOLATILITY_BREAKOUT -> new UiText(
                    "Volatility Breakout",
                    "Пробой по волатильности: вход когда движение превышает ожидаемую амплитуду (ATR/диапазон)"
            );

            // V) LEVELS / STRUCTURE
            case SUPPORT_RESISTANCE -> new UiText(
                    "Support / Resistance",
                    "Торговля от уровней поддержки/сопротивления: реакции от уровней и пробои"
            );
            case FIBONACCI_RETRACE -> new UiText(
                    "Fibonacci Retrace",
                    "Входы по откату к уровням Фибоначчи внутри тренда (ретрейсмент)"
            );
            case PRICE_ACTION -> new UiText(
                    "Price Action",
                    "Прайс-экшен: решения по структуре свечей и поведению цены без тяжёлых индикаторов"
            );

            // VI) GRIDS
            case GRID -> new UiText(
                    "Grid",
                    "Сетка ордеров: серия входов по шагу цены, фиксация прибыли на колебаниях"
            );
            case FIBONACCI_GRID -> new UiText(
                    "Fibonacci Grid",
                    "Сетка ордеров по уровням Фибоначчи: распределение входов/выходов по структуре рынка"
            );

            // VII) VOLUME
            case VOLUME_PROFILE -> new UiText(
                    "Volume Profile",
                    "Объёмный профиль: уровни интереса рынка по накопленным объёмам (POC/зоны)"
            );
            case VWAP -> new UiText(
                    "VWAP",
                    "VWAP: торговля относительно средней цены по объёму (перекос/возврат к VWAP)"
            );
            case ORDER_FLOW -> new UiText(
                    "Order Flow",
                    "Поток ордеров: анализ дисбаланса покупок/продаж (лента/стакан/дельта) — если данные доступны"
            );

            // VIII) AI
            case ML_CLASSIFICATION -> new UiText(
                    "ML Classification",
                    "ML-классификация сигналов: модель оценивает вероятность BUY/SELL по признакам рынка"
            );
            case RL_AGENT -> new UiText(
                    "RL Agent",
                    "RL-агент: выбирает действие (BUY/SELL/HOLD) на основе политики обучения с подкреплением"
            );
            case HYBRID -> new UiText(
                    "Hybrid",
                    "Гибрид: объединяет несколько подходов (индикаторы + ML/RL) с общей логикой риска"
            );

            // Дополнительно
            case DCA -> new UiText(
                    "DCA",
                    "Покупка частями: усреднение позиции по времени/цене с контролем суммарного риска"
            );
            case SMART_FUSION -> new UiText(
                    "Smart Fusion",
                    "Комбинированная AI-стратегия: объединяет сигналы нескольких модулей (индикаторы + ML + RL)"
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
