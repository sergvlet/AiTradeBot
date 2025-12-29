package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;
import com.chicu.aitradebot.service.StrategySettingsService;
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
@RequestMapping("/api/strategy")
public class StrategyToggleApiController {

    private final WebStrategyFacade webStrategyFacade;
    private final StrategySettingsService strategySettingsService;

    /**
     * POST /api/strategy/toggle
     * chatId=1&type=SCALPING
     */
    @PostMapping("/toggle")
    public ResponseEntity<ToggleResponse> toggle(
            @RequestParam Long chatId,
            @RequestParam StrategyType type
    ) {

        if (chatId == null || chatId <= 0) {
            return ResponseEntity.badRequest()
                    .body(ToggleResponse.error("Некорректный chatId"));
        }

        try {
            // 1️⃣ Берём актуальные настройки стратегии
            StrategySettings settings =
                    strategySettingsService.findLatest(chatId, type, null, null)
                            .orElseThrow(() ->
                                    new IllegalStateException("Настройки стратегии не найдены")
                            );

            log.info(
                    "🌐 [API] toggle strategy: chatId={}, type={}, exchange={}, network={}",
                    chatId,
                    type,
                    settings.getExchangeName(),
                    settings.getNetworkType()
            );

            // 2️⃣ Контекстный toggle
            StrategyRunInfo info =
                    webStrategyFacade.toggle(
                            chatId,
                            type,
                            settings.getExchangeName(),
                            settings.getNetworkType()
                    );

            return ResponseEntity.ok(
                    ToggleResponse.success(
                            info.isActive(),
                            info.getMessage(),
                            info
                    )
            );

        } catch (IllegalStateException ex) {
            return ResponseEntity.badRequest()
                    .body(ToggleResponse.error(ex.getMessage()));

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
