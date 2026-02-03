package com.chicu.aitradebot.ai.tuning.eval;

import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.Builder;
import org.jetbrains.annotations.Contract;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;

@Builder(toBuilder = true)
public record BacktestMetrics(

        // статус выполнения
        boolean ok,
        String reason,

        // идентификация (чтобы потом логировать/сохранять)
        Long chatId,
        StrategyType type,
        String symbol,
        String timeframe,
        Instant startAt,
        Instant endAt,

        // ключевые метрики
        BigDecimal profitPct,
        BigDecimal maxDrawdownPct,

        // статистика
        int trades,
        int wins,
        int losses,
        BigDecimal winRatePct,

        // параметры кандидата (диагностика)
        Map<String, Object> params
) {

    // -----------------------------------------------------
    // ✅ Совместимость (get*/is*)
    // -----------------------------------------------------

    @Contract(pure = true)
    public boolean isOk() {
        return ok;
    }

    public String getFailReason() {
        return reason;
    }

    public BigDecimal getProfitPct() {
        return profitPct;
    }

    public BigDecimal getMaxDrawdownPct() {
        return maxDrawdownPct;
    }

    public Integer getTradesCount() {
        return trades;
    }

    public Integer getTrades() {
        return trades;
    }

    public Integer getWins() {
        return wins;
    }

    public Integer getLosses() {
        return losses;
    }

    public BigDecimal getWinRatePct() {
        return winRatePct;
    }

    // -----------------------------------------------------
    // ✅ ВАЖНО: SCORE для тюнеров
    // WindowScalpingAutoTuner читает score/getScore/scoreTotal/getScoreTotal.
    // Делаем вычисляемый score без изменения структуры record.
    //
    // Формула: score = profitPct - drawdownPenalty
    // drawdownPenalty = maxDrawdownPct * 0.50
    // Если trades == 0 -> сильный штраф, чтобы тюнер выбирал кандидатов с реальными сделками.
    // -----------------------------------------------------

    public BigDecimal score() {
        return getScore();
    }

    public BigDecimal getScore() {
        BigDecimal p = nz(profitPct);
        BigDecimal dd = nz(maxDrawdownPct);

        // penalty = dd * 0.50
        BigDecimal penalty = dd.multiply(new BigDecimal("0.50"));

        BigDecimal s = p.subtract(penalty);

        // штраф за отсутствие сделок
        if (trades <= 0) {
            s = s.subtract(new BigDecimal("1.000"));
        }

        return s.setScale(6, RoundingMode.HALF_UP);
    }

    public BigDecimal scoreTotal() {
        return getScoreTotal();
    }

    public BigDecimal getScoreTotal() {
        return getScore();
    }

    // -----------------------------------------------------
    // ✅ Factories
    // -----------------------------------------------------

    public static BacktestMetrics fail(String reason) {
        return BacktestMetrics.builder()
                .ok(false)
                .reason(reason)
                .profitPct(BigDecimal.ZERO)
                .maxDrawdownPct(BigDecimal.ZERO)
                .trades(0)
                .wins(0)
                .losses(0)
                .winRatePct(BigDecimal.ZERO)
                .build();
    }

    public static BacktestMetrics stubOk(Long chatId,
                                         StrategyType type,
                                         String symbol,
                                         String timeframe,
                                         Map<String, Object> params,
                                         Instant startAt,
                                         Instant endAt) {

        return BacktestMetrics.builder()
                .ok(true)
                .reason("STUB_OK")
                .chatId(chatId)
                .type(type)
                .symbol(symbol)
                .timeframe(timeframe)
                .startAt(startAt)
                .endAt(endAt)
                .profitPct(BigDecimal.ZERO)
                .maxDrawdownPct(BigDecimal.ZERO)
                .trades(0)
                .wins(0)
                .losses(0)
                .winRatePct(BigDecimal.ZERO)
                .params(params)
                .build();
    }

    public static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
