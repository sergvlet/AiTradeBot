package com.chicu.aitradebot.web.facade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;

import java.math.BigDecimal;
import java.util.List;

public record StrategyUi(

        // === ИДЕНТИФИКАЦИЯ ===
        Long id,
        Long chatId,
        StrategyType type,
        String exchangeName,
        NetworkType networkType,

        // === СОСТОЯНИЕ ===
        boolean active,

        // === НАСТРОЙКИ ===
        String symbol,
        String timeframe,

        // === РИСК / ПАРАМЕТРЫ ===
        BigDecimal takeProfitPct,
        BigDecimal stopLossPct,
        BigDecimal commissionPct,
        BigDecimal riskPerTradePct,

        // === UI ===
        String title,
        String description,

        // === СТАТИСТИКА ===
        BigDecimal totalProfitPct,
        BigDecimal mlConfidence
) {

    // ================================================================
    // 🔁 PUBLIC MAPPER (используется facade)
    // ================================================================
    public static List<StrategyUi> fromSettings(List<StrategySettings> settings) {
        return settings.stream().map(StrategyUi::from).toList();
    }

    // ================================================================
    // 🔒 PRIVATE — только внутри UI
    // ================================================================
    private static StrategyUi from(StrategySettings s) {

        BigDecimal profit        = nz(s.getTotalProfitPct());
        BigDecimal mlConf        = nz(s.getMlConfidence());
        BigDecimal tp            = nz(s.getTakeProfitPct());
        BigDecimal sl            = nz(s.getStopLossPct());
        BigDecimal commission    = nz(s.getCommissionPct());
        BigDecimal riskPerTrade  = nz(s.getRiskPerTradePct());

        NetworkType network =
                s.getNetworkType() != null
                        ? s.getNetworkType()
                        : NetworkType.MAINNET;

        String exchange =
                s.getExchangeName() != null
                        ? s.getExchangeName().toString()
                        : "BINANCE";

        UiText ui = uiText(s.getType());

        return new StrategyUi(
                s.getId(),
                s.getChatId(),
                s.getType(),
                exchange,
                network,
                s.isActive(), // ⚠ runtime подставляется facade позже
                safe(s.getSymbol(), "—"),
                safe(s.getTimeframe(), "—"),
                tp,
                sl,
                commission,
                riskPerTrade,
                ui.title,
                ui.description,
                profit,
                mlConf
        );
    }

    // ================================================================
    // 🧩 EMPTY — когда нет записи в БД
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
                exchange,
                network,
                false,
                "—",
                "—",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                ui.title,
                "Стратегия ещё не настроена",
                BigDecimal.ZERO,
                BigDecimal.ZERO
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
    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static String safe(String v, String def) {
        return (v != null && !v.isBlank()) ? v : def;
    }

    private record UiText(String title, String description) {}
}
