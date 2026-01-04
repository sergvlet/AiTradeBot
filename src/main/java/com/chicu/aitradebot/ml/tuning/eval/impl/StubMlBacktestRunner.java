package com.chicu.aitradebot.ml.tuning.eval.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.ml.tuning.eval.BacktestMetrics;
import com.chicu.aitradebot.ml.tuning.eval.MlBacktestRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Service
@ConditionalOnMissingBean(MlBacktestRunner.class)
public class StubMlBacktestRunner implements MlBacktestRunner {

    @Override
    public BacktestMetrics run(Long chatId,
                               StrategyType type,
                               String symbolOverride,
                               String timeframeOverride,
                               Map<String, Object> candidateParams,
                               Instant startAt,
                               Instant endAt) {

        log.warn("🧪 ML BacktestRunner = STUB (type={}, symbol={}, tf={}) — подключи RealMlBacktestRunner",
                type, symbolOverride, timeframeOverride);

        // ВАЖНО: ok=true, чтобы пайплайн тюнера не ломался.
        // Когда подключишь реальный — заменишь на реальный расчёт.
        return BacktestMetrics.stubOk(chatId, type, symbolOverride, timeframeOverride, candidateParams, startAt, endAt);
    }
}
