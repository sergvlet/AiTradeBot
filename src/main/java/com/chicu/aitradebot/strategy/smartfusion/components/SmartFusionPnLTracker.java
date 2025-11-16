package com.chicu.aitradebot.strategy.smartfusion.components;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * 📊 SmartFusionPnLTracker — трекает прибыль, сделки и статистику по каждой паре.
 */
@Component
@Slf4j
public class SmartFusionPnLTracker {

    /** История PnL по chatId + symbol */
    private final Map<String, List<Double>> pnlHistory = new HashMap<>();

    /** Вспомогательный ключ */
    private String key(long chatId, String symbol) {
        return chatId + "|" + symbol.toUpperCase();
    }

    // ================== Методы PnL ==================

    public void recordTrade(long chatId, String symbol, double pnlUsd) {
        pnlHistory.computeIfAbsent(key(chatId, symbol), k -> new ArrayList<>()).add(pnlUsd);
    }

    public double getTotalProfitUsd(long chatId, String symbol) {
        return pnlHistory.getOrDefault(key(chatId, symbol), List.of()).stream()
                .mapToDouble(Double::doubleValue)
                .sum();
    }

    public double getTotalProfitPct(long chatId, String symbol) {
        List<Double> trades = pnlHistory.getOrDefault(key(chatId, symbol), List.of());
        if (trades.isEmpty()) return 0;
        double total = getTotalProfitUsd(chatId, symbol);
        double avgTrade = trades.stream().mapToDouble(Double::doubleValue).average().orElse(1);
        return avgTrade == 0 ? 0 : (total / (Math.abs(avgTrade) * trades.size())) * 100.0;
    }

    public int getTradeCount(long chatId, String symbol) {
        return pnlHistory.getOrDefault(key(chatId, symbol), List.of()).size();
    }

    public double getWinRate(long chatId, String symbol) {
        List<Double> trades = pnlHistory.getOrDefault(key(chatId, symbol), List.of());
        if (trades.isEmpty()) return 0;
        long wins = trades.stream().filter(v -> v > 0).count();
        return (wins * 100.0) / trades.size();
    }

    /**
     * 📉 Возвращает текущую дневную просадку в процентах (от максимального баланса за день).
     */
    public double getDailyDrawdownPct(long chatId, String symbol) {
        List<Double> trades = pnlHistory.getOrDefault(key(chatId, symbol), List.of());
        if (trades.isEmpty()) return 0;

        double peak = 0.0;
        double equity = 0.0;
        double maxDrawdown = 0.0;

        for (double pnl : trades) {
            equity += pnl;
            if (equity > peak) peak = equity;
            double dd = (peak - equity) / (peak == 0 ? 1 : peak) * 100.0;
            if (dd > maxDrawdown) maxDrawdown = dd;
        }

        return Math.round(maxDrawdown * 100.0) / 100.0; // округляем до 2 знаков
    }

    /** Возвращает историю PnL для графика */
    public List<Double> getHistory(long chatId, String symbol) {
        return new ArrayList<>(pnlHistory.getOrDefault(key(chatId, symbol), List.of()));
    }

    /** Для будущих индикаторов (EMA/RSI/Bollinger) — возвращает заглушки */
    public Map<String, List<Double>> getIndicators(long chatId, String symbol) {
        return Map.of(
                "emaFast", List.of(),
                "emaSlow", List.of(),
                "rsi", List.of(),
                "bbUpper", List.of(),
                "bbLower", List.of()
        );
    }
    /**
     * 🔄 Обновляет индикаторы (EMA, RSI, Bollinger и т.д.)
     */
    public void updateIndicators(long chatId, String symbol, Map<String, Double> indicators) {
        // Здесь можно просто логировать, или сохранять в память для анализа.
        log.debug("📈 Обновлены индикаторы для {}: {}", symbol, indicators);
    }

    /**
     * 💰 Расширенная запись сделки (для SmartFusionStrategy).
     *
     * @param chatId        пользователь
     * @param symbol        пара
     * @param profitUsd     прибыль в USD
     * @param profitPct     прибыль в %
     * @param win           выигрышная ли сделка
     * @param balanceAfter  баланс после сделки
     */
    public void recordTrade(
            long chatId,
            String symbol,
            double profitUsd,
            double profitPct,
            boolean win,
            double balanceAfter
    ) {
        // Сохраняем PnL в основную историю
        recordTrade(chatId, symbol, profitUsd);

        // Можно добавить доп. метрику: win/lose, balanceAfter
        log.info("💾 Запись сделки {}: pnl={} USD ({})% win={} balanceAfter={}",
                symbol, profitUsd, profitPct, win, balanceAfter);
    }

}
