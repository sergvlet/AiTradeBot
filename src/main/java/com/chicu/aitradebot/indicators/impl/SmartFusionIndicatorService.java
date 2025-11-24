package com.chicu.aitradebot.indicators.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.indicators.IndicatorResponse;
import com.chicu.aitradebot.indicators.IndicatorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.List;

@Slf4j
@Service
public class SmartFusionIndicatorService implements IndicatorService {

    @Override
    public IndicatorResponse loadIndicators(
            Long chatId,
            StrategyType type,
            String symbol,
            String timeframe
    ) {
        return new IndicatorResponse(
                Map.of(
                        "ema20", List.of(),
                        "ema50", List.of(),
                        "bbUpper", List.of(),
                        "bbLower", List.of()
                ),
                Map.of(
                        "name", "SmartFusion",
                        "version", "1.0"
                )
        );
    }
}
