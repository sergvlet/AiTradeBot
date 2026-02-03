package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.ai.tuning.eval.BacktestMetrics;
import com.chicu.aitradebot.ai.tuning.eval.BacktestPort;
import com.chicu.aitradebot.ai.tuning.eval.MlBacktestRunner;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Primary
@Service
@RequiredArgsConstructor
public class RealMlBacktestRunner implements MlBacktestRunner {

    private final BacktestPort backtestPort;

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

        // exchange/network берутся из StrategySettings внутри JpaBacktestPort (ты это уже сделал)
        // но интерфейс runner-а оставляем с ex/net для совместимости/логов/будущего.
        return backtestPort.backtest(
                chatId,
                type,
                symbolOverride,
                timeframeOverride,
                candidateParams,
                startAt,
                endAt
        );
    }
}
