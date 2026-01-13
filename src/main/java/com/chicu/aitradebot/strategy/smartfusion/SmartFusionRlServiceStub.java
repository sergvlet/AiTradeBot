// src/main/java/com/chicu/aitradebot/strategy/smartfusion/SmartFusionRlServiceStub.java
package com.chicu.aitradebot.strategy.smartfusion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Заглушка RL.
 * Возвращает HOLD с уверенность 0.50, чтобы SmartFusionStrategyV4 работала без падений.
 */
@Slf4j
@Service
@Primary
public class SmartFusionRlServiceStub implements SmartFusionRlService {

    @Override
    public RlDecision decide(Long chatId,
                             String agentKey,
                             String symbol,
                             String timeframe,
                             SmartFusionFeatures features) {

        RlDecision out = new RlDecision("HOLD", 0.50);

        if (log.isDebugEnabled()) {
            log.debug("[SmartFusionRL][STUB] chatId={} sym={} tf={} agentKey={} -> {}:{}",
                    chatId, symbol, timeframe, agentKey, out.action(), out.confidence());
        }
        return out;
    }
}
