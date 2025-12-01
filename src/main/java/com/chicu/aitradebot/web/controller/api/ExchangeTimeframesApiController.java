package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/exchange/timeframes")
@RequiredArgsConstructor
public class ExchangeTimeframesApiController {

    private final ExchangeClientFactory clientFactory;

    @GetMapping
    public List<String> getTimeframes(
            @RequestParam String exchange,
            @RequestParam NetworkType networkType
    ) {
        try {
            ExchangeClient client = clientFactory.get(exchange, networkType);
            if (client == null) {
                log.warn("⚠️ Клиент {} [{}] не найден", exchange, networkType);
                return List.of();
            }

            List<String> list = client.getAvailableTimeframes();
            log.info("⏱ Получено {} таймфреймов с {} [{}]", list.size(), exchange, networkType);
            return list;
        } catch (Exception e) {
            log.error("❌ Ошибка получения таймфреймов {} [{}]: {}", exchange, networkType, e.getMessage());
            return List.of();
        }
    }
}
