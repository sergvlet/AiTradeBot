package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.ai.tuning.eval.BacktestMetrics;
import com.chicu.aitradebot.ai.tuning.eval.MlBacktestRunner;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
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
                               String exchange,
                               NetworkType network,
                               String symbolOverride,
                               String timeframeOverride,
                               Map<String, Object> candidateParams,
                               Instant startAt,
                               Instant endAt) {

        log.warn("🧪 ML BacktestRunner = STUB (type={}, ex={}, net={}, symbol={}, tf={}) — подключи RealMlBacktestRunner",
                type, exchange, network, symbolOverride, timeframeOverride);

        // ok=true, чтобы пайплайн тюнера не ломался
        return BacktestMetrics.stubOk(chatId, type, symbolOverride, timeframeOverride, candidateParams, startAt, endAt);
    }
}
