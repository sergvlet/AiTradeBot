package com.chicu.aitradebot.ml.tuning.eval.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.ml.tuning.eval.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Service
@Primary
@RequiredArgsConstructor
public class RealMlBacktestRunner implements MlBacktestRunner {

    private final BacktestService backtestService;

    @Override
    public BacktestMetrics run(Long chatId,
                               StrategyType type,
                               String symbolOverride,
                               String timeframeOverride,
                               Map<String, Object> candidateParams,
                               Instant startAt,
                               Instant endAt) {

        return backtestService.run(chatId, type, symbolOverride, timeframeOverride, candidateParams, startAt, endAt);
    }
}
