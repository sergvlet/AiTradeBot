// src/main/java/com/chicu/aitradebot/strategy/rl/DefaultRlAgentService.java
package com.chicu.aitradebot.strategy.rl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Заглушка RL агента для старта контекста.
 * Позже заменишь на реальную интеграцию (Python/RL/registry).
 */
@Slf4j
@Service
public class DefaultRlAgentService implements RlAgentService {

    @Override
    public RlDecision decide(Long chatId, String symbol, String timeframe, RlState state) {

        // Нейтральный stub: чаще HOLD, иногда BUY/SELL
        double r = ThreadLocalRandom.current().nextDouble(0.0, 1.0);

        RlAction action;
        if (r < 0.10) action = RlAction.BUY;
        else if (r < 0.20) action = RlAction.SELL;
        else action = RlAction.HOLD;

        // confidence 0.45..0.65 чтобы стратегия чаще фильтровала по minConfidence
        BigDecimal conf = BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(0.45, 0.65));

        // ✅ Третий аргумент обязателен (agentKey/source)
        String agentKey = "rl_stub";

        if (log.isDebugEnabled()) {
            log.debug("[RL][STUB] chatId={} sym={} tf={} action={} conf={} agentKey={}",
                    chatId, symbol, timeframe, action, conf, agentKey);
        }

        return new RlDecision(action, conf, agentKey);
    }
}
