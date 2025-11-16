package com.chicu.aitradebot.strategy.rsie;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@StrategyBinding(StrategyType.RSI_EMA)
public class RsiEmaStrategy implements TradingStrategy {

    private boolean active = false;

    @Override
    public void start() {
        active = true;
        log.info("📈 RSI+EMA strategy started");
    }

    @Override
    public void stop() {
        active = false;
        log.info("📉 RSI+EMA strategy stopped");
    }

    @Override
    public boolean isActive() {
        return active;
    }
}
