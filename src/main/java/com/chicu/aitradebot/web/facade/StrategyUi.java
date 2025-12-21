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

        // === РИСК / ПАРАМЕТРЫ (то что у тебя печатается как null) ===
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

    public static List<StrategyUi> fromSettings(List<StrategySettings> settings) {
        return settings.stream().map(StrategyUi::from).toList();
    }

    private static StrategyUi from(StrategySettings s) {

        // --- дефолты, чтобы Thymeleaf НЕ падал и UI не показывал null ---
        BigDecimal profit        = bd(s.getTotalProfitPct());
        BigDecimal mlConf        = bd(s.getMlConfidence());
        BigDecimal tp            = bd(s.getTakeProfitPct());
        BigDecimal sl            = bd(s.getStopLossPct());
        BigDecimal commission    = bd(s.getCommissionPct());
        BigDecimal riskPerTrade  = bd(s.getRiskPerTradePct());

        NetworkType network = (s.getNetworkType() != null) ? s.getNetworkType() : NetworkType.MAINNET;

        // exchangeName у тебя в сущности может быть enum или String — поэтому максимально безопасно:
        String exchange = (s.getExchangeName() != null) ? s.getExchangeName().toString() : "—";

        String title;
        String desc;

        switch (s.getType()) {
            case SCALPING -> {
                title = "Scalping";
                desc  = "Быстрые сделки на малых движениях цены";
            }
            case FIBONACCI_GRID -> {
                title = "Fibonacci Grid";
                desc  = "Сетка ордеров по уровням Фибоначчи";
            }
            case RSI_EMA -> {
                title = "RSI + EMA";
                desc  = "Трендовая стратегия на RSI и EMA";
            }
            case ML_INVEST -> {
                title = "ML Invest";
                desc  = "Инвестиционная стратегия с машинным обучением";
            }
            case SMART_FUSION -> {
                title = "Smart Fusion";
                desc  = "Комбинированная AI-стратегия";
            }
            default -> {
                title = s.getType().name();
                desc  = "Стратегия без UI-описания";
            }
        }

        return new StrategyUi(
                s.getId(),
                s.getChatId(),
                s.getType(),
                exchange,
                network,
                s.isActive(),
                safeText(s.getSymbol(), "—"),
                safeText(s.getTimeframe(), "—"),
                tp,
                sl,
                commission,
                riskPerTrade,
                title,
                desc,
                profit,
                mlConf
        );
    }

    private static BigDecimal bd(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static String safeText(String v, String def) {
        return (v != null && !v.isBlank()) ? v : def;
    }
}
