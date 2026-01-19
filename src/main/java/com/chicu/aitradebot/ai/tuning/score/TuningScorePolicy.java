package com.chicu.aitradebot.ai.tuning.score;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.ai.tuning.eval.BacktestMetrics;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;


@Configuration
public interface TuningScorePolicy {

    StrategyType supports();

    BigDecimal score(BacktestMetrics metrics);
}
