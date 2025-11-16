package com.chicu.aitradebot.strategy.smartfusion.components;

import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettings;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * SmartFusionRiskManager — отвечает за управление рисками.
 *
 * 📊 Основные задачи:
 *  - Проверка допустимости сделки (volatility / loss limit)
 *  - Расчёт TP/SL на основе ATR и RL множителей
 *  - SmartSizing (динамический размер позиции)
 *  - Расчёт ожидаемой чистой прибыли
 *
 * ⚙️ Без хардкода — все параметры подставляются из SmartFusionStrategySettings.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SmartFusionRiskManager {

    private final SmartFusionPnLTracker pnlTracker;

    /**
     * Проверяет, можно ли открывать новую сделку:
     * - не превышен лимит просадки
     * - волатильность в допустимых пределах
     */
    public boolean allowTrade(long chatId,
                              String symbol,
                              SmartFusionStrategySettings cfg,
                              double volatilityPct) {

        double maxVol = nz(cfg.getVolatilityThresholdPct(), 2.0);  // %
        double maxLoss = nz(cfg.getDailyLossLimitPct(), 3.0);      // %
        double currentLoss = pnlTracker.getDailyDrawdownPct(chatId, symbol);

        if (volatilityPct > maxVol) {
            log.warn("🚫 Volatility Shield: скачок {}% > {}%", r(volatilityPct), r(maxVol));
            return false;
        }

        if (Math.abs(currentLoss) > maxLoss) {
            log.warn("🚫 Daily Loss Limit: текущая просадка {}% > лимита {}%", r(currentLoss), r(maxLoss));
            return false;
        }

        return true;
    }

    /**
     * Рассчитывает параметры сделки: TP, SL, объём и ожидаемую прибыль.
     */
    public TradePlan computeTradePlan(SmartFusionRlAgent.RlDecision rl,
                                      double atrValue,
                                      double entryPrice,
                                      SmartFusionStrategySettings cfg,
                                      boolean isBuy) {

        double tpMult = rl.getTpAtrMult();
        double slMult = rl.getSlAtrMult();
        double sizePct = rl.getSizePct();

        // Размер позиции (в USDT)
        double positionValue = cfg.getCapitalUsd() * (sizePct / 100.0);

        // TP / SL по ATR
        double tpPrice = isBuy
                ? entryPrice + atrValue * tpMult
                : entryPrice - atrValue * tpMult;

        double slPrice = isBuy
                ? entryPrice - atrValue * slMult
                : entryPrice + atrValue * slMult;

        // Комиссия из настроек
        double commissionPct = nz(cfg.getCommissionPct(), 0.1);
        double netProfitPct = ((tpPrice - entryPrice) / entryPrice) * 100.0 - commissionPct;

        TradePlan plan = TradePlan.builder()
                .entryPrice(round(entryPrice))
                .tpPrice(round(tpPrice))
                .slPrice(round(slPrice))
                .positionValue(round(positionValue))
                .sizePct(round(sizePct))
                .commissionPct(round(commissionPct))
                .expectedNetProfitPct(round(netProfitPct))
                .build();

        log.debug("📊 TradePlan {} → {}", isBuy ? "BUY" : "SELL", plan);
        return plan;
    }

    /**
     * Расчёт волатильности (%) между последними двумя свечами.
     */
    public double calcVolatilityPct(List<SmartFusionCandleService.Candle> candles) {
        if (candles.size() < 2) return 0.0;
        double last = candles.get(candles.size() - 1).close();
        double prev = candles.get(candles.size() - 2).close();
        return Math.abs((last - prev) / prev) * 100.0;
    }

    /**
     * Расчёт абсолютного размера позиции на основе капитала и процента риска.
     */
    public double calcPositionSize(SmartFusionStrategySettings cfg) {
        double capital = cfg.getCapitalUsd();
        double riskPct = cfg.getRiskPerTradePct();
        double size = capital * (riskPct / 100.0);
        log.debug("💰 calcPositionSize: capital={} riskPct={} → size={}", capital, riskPct, round(size));
        return size;
    }

    // === helpers ===

    private static double nz(Double v, double def) {
        return v == null ? def : v;
    }

    private static double round(double v) {
        return new BigDecimal(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static String r(double v) {
        return String.format("%.2f", v);
    }

    // --- DTO ---
    @Data
    @Builder
    @AllArgsConstructor
    public static class TradePlan {
        private double entryPrice;
        private double tpPrice;
        private double slPrice;
        private double positionValue;
        private double sizePct;
        private double commissionPct;
        private double expectedNetProfitPct;

        @Override
        public String toString() {
            return String.format("TP=%.2f SL=%.2f Size=%.2f%% Value=%.2fUSDT",
                    tpPrice, slPrice, sizePct, positionValue);
        }
    }
}
