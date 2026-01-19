package com.chicu.aitradebot.ai.tuning.eval;

import java.math.BigDecimal;
import java.time.Instant;

public record CandleBar(
        Instant openTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume
) {}
