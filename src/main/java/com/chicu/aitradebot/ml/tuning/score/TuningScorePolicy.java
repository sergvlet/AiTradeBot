package com.chicu.aitradebot.ml.tuning.score;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.ml.tuning.eval.BacktestMetrics;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;


@Configuration
public interface TuningScorePolicy {

    StrategyType supports();

    BigDecimal score(BacktestMetrics metrics);
}
