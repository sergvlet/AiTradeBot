package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.ai.tuning.eval.BacktestMetrics;
import com.chicu.aitradebot.ai.tuning.eval.MlBacktestRunner;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "ai.ml.backtest", name = "use-stub", havingValue = "true")
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

        log.warn("🧪 ML BacktestRunner = STUB (type={}, ex={}, net={}, symbol={}, tf={})",
                type, exchange, network, symbolOverride, timeframeOverride);

        return BacktestMetrics.builder()
                .ok(true)
                .reason("STUB")
                .chatId(chatId)
                .type(type)
                .symbol(symbolOverride)
                .timeframe(timeframeOverride)
                .startAt(startAt)
                .endAt(endAt)
                .profitPct(BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP))
                .maxDrawdownPct(BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP))
                .trades(0)
                .wins(0)
                .losses(0)
                .winRatePct(BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP))
                .params(candidateParams)
                .build();
    }
}