package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.orchestrator.StrategyFacade;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
public class StrategyControlApiController {

    private final StrategyFacade facade;

    @PostMapping(value = "/toggle", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> toggle(
            @RequestParam long chatId,
            @RequestParam StrategyType type,
            @RequestParam(defaultValue = "BTCUSDT") String symbol
    ) {
        Map<String, Object> body = new HashMap<>();
        try {
            boolean running = facade.isRunning(chatId, type);

            if (running) {
                facade.stop(chatId, type);
                log.info("⏹ Стратегия {} остановлена (chatId={})", type, chatId);
                body.put("status", "stopped");
                body.put("active", false);
                body.put("type", type.name());
                body.put("redirect", "");
            } else {
                facade.start(chatId, type, symbol);
                log.info("🚀 Стратегия {} запущена (chatId={}, symbol={})", type, chatId, symbol);
                body.put("status", "started");
                body.put("active", true);
                body.put("type", type.name());
                body.put("redirect", "/strategies/" + type.name().toLowerCase()
                                     + "/dashboard?chatId=" + chatId + "&symbol=" + symbol);
            }

            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("❌ Ошибка переключения стратегии {}: {}", type, e.getMessage(), e);
            body.put("status", "error");
            body.put("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            body.put("active", false);
            return ResponseEntity.internalServerError().body(body);
        }
    }

    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> status(
            @RequestParam long chatId,
            @RequestParam StrategyType type
    ) {
        Map<String, Object> body = new HashMap<>();
        try {
            StrategyRunInfo info = facade.status(chatId, type);
            body.put("status", info != null && info.isActive() ? "running" : "stopped");
            body.put("active", info != null && info.isActive());
            body.put("symbol", info != null && info.getSymbol() != null ? info.getSymbol() : "—");
            body.put("thread", info != null && info.getThreadName() != null ? info.getThreadName() : "n/a");
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("❌ Ошибка при получении статуса стратегии {}: {}", type, e.getMessage(), e);
            body.put("status", "error");
            body.put("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().body(body);
        }
    }
}
