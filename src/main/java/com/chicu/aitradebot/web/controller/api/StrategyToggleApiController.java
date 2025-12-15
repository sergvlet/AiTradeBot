package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;
import com.chicu.aitradebot.web.facade.WebStrategyFacade;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/strategy") // ← ОСТАВЛЯЕМ ЭТОТ ПУТЬ
public class StrategyToggleApiController {

    private final WebStrategyFacade webStrategyFacade;

    /**
     * POST /api/strategy/toggle
     * chatId=1&type=SMART_FUSION&symbol=BTCUSDT&timeframe=1m
     */
    @PostMapping("/toggle")
    public ResponseEntity<ToggleResponse> toggle(
            @RequestParam Long chatId,
            @RequestParam StrategyType type,
            @RequestParam String symbol,
            @RequestParam(required = false, defaultValue = "1m") String timeframe
    ) {
        log.info("🌐 [WEB] toggle strategy: chatId={}, type={}, symbol={}, timeframe={}",
                chatId, type, symbol, timeframe);

        // === Валидация ===
        if (chatId == null || chatId <= 0) {
            return ResponseEntity.badRequest()
                    .body(ToggleResponse.error("Некорректный chatId"));
        }

        if (symbol == null || symbol.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ToggleResponse.error("Не указан символ"));
        }

        try {
            // ⚡ ВАЖНО — вызываем единый фасад
            StrategyRunInfo info = webStrategyFacade.toggleStrategy(chatId, type, symbol, timeframe);

            return ResponseEntity.ok(
                    ToggleResponse.success(info.isActive(), info.getMessage(), info)
            );

        } catch (Exception ex) {
            log.error("❌ Ошибка переключения стратегии", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ToggleResponse.error("Ошибка переключения стратегии"));
        }
    }

    // =============================================================
    // DTO ответа
    // =============================================================
    @Data
    @AllArgsConstructor
    public static class ToggleResponse {

        private boolean success;
        private boolean active;
        private String message;
        private StrategyRunInfo info;

        public static ToggleResponse success(boolean active, String msg, StrategyRunInfo info) {
            return new ToggleResponse(true, active, msg, info);
        }

        public static ToggleResponse error(String msg) {
            return new ToggleResponse(false, false, msg, null);
        }
    }
}
