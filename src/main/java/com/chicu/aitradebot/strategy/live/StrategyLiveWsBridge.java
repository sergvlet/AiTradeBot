package com.chicu.aitradebot.strategy.live;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyLiveWsBridge {

    private final SimpMessagingTemplate ws;

    /**
     * 🔥 LIVE v4
     * ЕДИНЫЙ маршрут:
     * /topic/strategy/{chatId}/{strategyType}
     */
    public void publish(StrategyLiveEvent ev) {

        if (ev == null) {
            log.warn("WS publish skipped: event is null");
            return;
        }

        if (ev.getChatId() == null || ev.getStrategyType() == null) {
            log.warn("WS publish skipped: chatId or strategyType is null → {}", ev);
            return;
        }

        String dest = "/topic/strategy/"
                      + ev.getChatId()
                      + "/"
                      + ev.getStrategyType().name();

        log.trace("📡 WS SEND {} → {}", dest, ev.getType());

        ws.convertAndSend(dest, ev);
    }
}
