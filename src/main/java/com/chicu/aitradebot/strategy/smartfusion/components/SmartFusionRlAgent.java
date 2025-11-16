package com.chicu.aitradebot.strategy.smartfusion.components;

import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettings;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SmartFusion RL Policy (уровень 3)
 * - хранит простое RL-состояние по ключу (chatId|symbol)
 * - на основе вознаграждения (reward) мягко подстраивает:
 *   * множители ATR для TP/SL
 *   * размер позиции (Smart Sizing, в % депозита)
 *
 * Принципы:
 *  - reward = pnlPct - max(0, drawdownPenalty)
 *  - EWMA (экспоненциальное среднее вознаграждения) с альфой rlAlpha
 *  - шаги адаптации малы (rlStepTp, rlStepSl, rlStepSize), чтобы не "дёргать" рынок
 *  - границы параметров берём из настроек или используем безопасные дефолты
 *
 * Позже состояние можно вынести в БД (таблица smart_fusion_rl_state).
 */
@Component
@Slf4j
public class SmartFusionRlAgent {

    /** Состояние по ключу user|symbol */
    private final Map<String, RlState> stateMap = new ConcurrentHashMap<>();

    private static String key(long chatId, String symbol) {
        return chatId + "|" + Objects.toString(symbol, "UNKNOWN");
    }

    /**
     * Получить актуальные параметры перед входом (без изменения состояния).
     * Используется при подготовке ордера BUY/SELL.
     */
    public RlDecision suggest(long chatId, String symbol, SmartFusionStrategySettings cfg) {
        var s = stateMap.computeIfAbsent(key(chatId, symbol), k -> initialState(cfg));
        // Вернём аккуратно округлённые значения (для читабельности логов/метрик)
        return RlDecision.builder()
                .tpAtrMult(round(s.tpAtrMult))
                .slAtrMult(round(s.slAtrMult))
                .sizePct(round(s.sizePct))
                .avgReward(round(s.avgReward))
                .build();
    }

    /**
     * Обновить RL-состояние после закрытия сделки.
     *
     * @param pnlPct         фактический PnL сделки в %, напр. +0.8 значит +0.8%
     * @param maxDrawdownPct макс. просадка по позиции в %, напр. 1.2 значит -1.2% просели
     */
    public RlDecision updateAfterTrade(long chatId,
                                       String symbol,
                                       SmartFusionStrategySettings cfg,
                                       double pnlPct,
                                       double maxDrawdownPct) {
        String k = key(chatId, symbol);
        var s = stateMap.computeIfAbsent(k, x -> initialState(cfg));

        // --- 1) Reward -------------------------------------------------------
        double drawdownPenaltyFloor = nz(cfg.getRlDrawdownPenaltyFloor(), 0.8); // сколько из просадки штрафуем (0..1)
        double penalty = Math.max(0.0, maxDrawdownPct * drawdownPenaltyFloor);
        double reward = pnlPct - penalty;

        double alpha = clamp(nz(cfg.getRlAlpha(), 0.2), 0.01, 0.9);             // "скорость" обучения
        s.avgReward = (1 - alpha) * s.avgReward + alpha * reward;

        // --- 2) Адаптация параметров ----------------------------------------
        double stepTp   = clamp(nz(cfg.getRlStepTp(),   0.05), 0.005, 0.5);     // шаг изменения TP множителя ATR
        double stepSl   = clamp(nz(cfg.getRlStepSl(),   0.05), 0.005, 0.5);     // шаг изменения SL множителя ATR
        double stepSize = clamp(nz(cfg.getRlStepSize(), 0.2),  0.05,  5.0);     // шаг изменения размера позиции (в %)

        // Границы
        double tpMin = clamp(nz(cfg.getRlMinTpMult(), 0.6), 0.1, 10);
        double tpMax = clamp(nz(cfg.getRlMaxTpMult(), 2.5), 0.1, 10);
        double slMin = clamp(nz(cfg.getRlMinSlMult(), 0.3), 0.05,10);
        double slMax = clamp(nz(cfg.getRlMaxSlMult(), 1.5), 0.05,10);

        double sizeMin = clamp(nz(cfg.getSmartSizingMinPct(), 1.0), 0.1, 50.0);
        double sizeMax = clamp(nz(cfg.getSmartSizingMaxPct(), 5.0), 0.2, 90.0);

        // Правило:
        //  * если средняя награда > 0 — можно чуть смелее:
        //      - TP чуть выше, SL чуть "уже" (меньше множитель → ближе стоп), размер ближе к max
        //  * если средняя награда <= 0 — консервативнее:
        //      - TP ниже, SL "шире", размер ближе к min
        if (s.avgReward > 0) {
            s.tpAtrMult = clamp(s.tpAtrMult + stepTp, tpMin, tpMax);
            s.slAtrMult = clamp(s.slAtrMult - stepSl, slMin, slMax);
            s.sizePct   = clamp(s.sizePct + stepSize, sizeMin, sizeMax);
        } else {
            s.tpAtrMult = clamp(s.tpAtrMult - stepTp, tpMin, tpMax);
            s.slAtrMult = clamp(s.slAtrMult + stepSl, slMin, slMax);
            s.sizePct   = clamp(s.sizePct - stepSize, sizeMin, sizeMax);
        }

        log.info("🧠 RL update [{}]: reward={}, avgReward={}, tpMult={}, slMult={}, sizePct={}",
                k, r(reward), r(s.avgReward), r(s.tpAtrMult), r(s.slAtrMult), r(s.sizePct));

        return RlDecision.builder()
                .tpAtrMult(round(s.tpAtrMult))
                .slAtrMult(round(s.slAtrMult))
                .sizePct(round(s.sizePct))
                .avgReward(round(s.avgReward))
                .build();
    }

    // ------------------------- Внутренняя модель состояния -------------------

    private RlState initialState(SmartFusionStrategySettings cfg) {
        // Начальные множители берём из настроек (или хорошие дефолты).
        double tp = clamp(nz(cfg.getTakeProfitAtrMult(), 0.9), 0.1, 10);
        double sl = clamp(nz(cfg.getStopLossAtrMult(),   0.6), 0.05,10);
        double sz = clamp(nz(cfg.getSmartSizingStartPct(), 1.0), 0.1, 90.0);

        return RlState.builder()
                .tpAtrMult(tp)
                .slAtrMult(sl)
                .sizePct(sz)
                .avgReward(0.0)
                .build();
    }

    private static double nz(Double v, double def) {
        return v == null ? def : v;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double round(double v) {
        return new BigDecimal(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static String r(double v) {
        return String.format("%.4f", v);
    }

    // --- DTO: внутреннее состояние ----
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class RlState {
        double tpAtrMult;  // множитель ATR для TP
        double slAtrMult;  // множитель ATR для SL
        double sizePct;    // размер позиции в % депозита (Smart Sizing)
        double avgReward;  // EWMA вознаграждения
    }

    // --- DTO: решение для стратегии ----
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RlDecision {
        private double tpAtrMult;
        private double slAtrMult;
        private double sizePct;
        private double avgReward;
    }
}
