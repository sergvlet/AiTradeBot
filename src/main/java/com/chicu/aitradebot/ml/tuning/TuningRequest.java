package com.chicu.aitradebot.ml.tuning;

import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.Builder;

import java.time.Instant;

@Builder
public record TuningRequest(
        Long chatId,
        StrategyType strategyType,

        // опционально: если хотим запускать на конкретном символе/таймфрейме
        String symbol,
        String timeframe,

        // опционально: период обучения/оценки
        Instant startAt,
        Instant endAt,

        // seed/версия/метки — позже (но запрос уже готов расширяться без ломки)
        Long seed
) {}
