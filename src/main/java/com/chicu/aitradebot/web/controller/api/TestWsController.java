package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

@RestController
@RequiredArgsConstructor
public class TestWsController {

    private final StrategyLivePublisher live;

    @PostMapping("/api/test/ws/candle")
    public String testCandle() {

        live.pushCandleOhlc(
                1L,
                StrategyType.SCALPING,
                "BTCUSDT",
                "1m",
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(110),
                BigDecimal.valueOf(90),
                BigDecimal.valueOf(105),
                BigDecimal.ONE,
                Instant.now()
        );

        live.pushPriceTick(
                1L,
                StrategyType.SCALPING,
                "BTCUSDT",
                "1m",
                BigDecimal.valueOf(105),
                Instant.now()
        );

        return "OK";
    }
}
