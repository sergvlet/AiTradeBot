package com.chicu.aitradebot.ai.tuning;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.Builder;

import java.time.Instant;

@Builder
public record TuningRequest(
        Long chatId,
        StrategyType strategyType,

        // ✅ контекст биржи/сети (нужно для multi-exchange и testnet/mainnet)
        String exchange,
        NetworkType network,

        // ✅ параметры датасета (берём из StrategySettings)
        String symbol,
        String timeframe,
        Integer candlesLimit,

        // опционально: период обучения/оценки
        Instant startAt,
        Instant endAt,

        // seed/версия/метки — позже
        Long seed,

        // ✅ зачем вызвали тюнинг (warmup/periodic/after-close)
        String reason
) {}
