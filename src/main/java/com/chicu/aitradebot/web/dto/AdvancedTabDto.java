package com.chicu.aitradebot.web.dto;

import com.chicu.aitradebot.domain.enums.AdvancedControlMode;

import java.math.BigDecimal;
import java.time.Instant;

public record AdvancedTabDto(
        boolean active,
        AdvancedControlMode advancedControlMode,

        BigDecimal mlConfidence,
        BigDecimal totalProfitPct,

        Instant updatedAt,
        Instant startedAt,
        Instant stoppedAt,

        String accountAsset,
        String symbol,
        String timeframe,

        String strategyAdvancedHtml,
        boolean strategyCanEdit
) {}
