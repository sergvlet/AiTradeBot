package com.chicu.aitradebot.ml.tuning.scalping;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.ml.tuning.eval.BacktestMetrics;
import com.chicu.aitradebot.ml.tuning.score.TuningScorePolicy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component("scalpingScorePolicy")
public class ScalpingScorePolicy implements TuningScorePolicy {

    @Override
    public StrategyType supports() {
        return StrategyType.SCALPING;
    }

    @Override
    public BigDecimal score(BacktestMetrics m) {
        if (m == null) return BigDecimal.valueOf(-1_000_000);
        if (!m.ok()) return BigDecimal.valueOf(-1_000_000);

        BigDecimal profit = nz(m.profitPct());          // %
        BigDecimal dd = nz(m.maxDrawdownPct());         // %
        int trades = m.trades();

        BigDecimal score = profit
                .subtract(dd.multiply(new BigDecimal("0.6")))
                .add(BigDecimal.valueOf(Math.log10(trades + 1)).multiply(new BigDecimal("0.2")));

        if (trades < 10) score = score.subtract(new BigDecimal("5"));

        return score.setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
