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

import java.util.Comparator;
import java.util.List;

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
     *
     * ⚠️ ВАЖНО:
     * Этот endpoint НЕ принимает exchange/network, поэтому мы должны выбрать "базовый" контекст.
     * Выбор:
     * - сначала active=true (если есть)
     * - иначе по updatedAt desc, затем id desc
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
        if (type == null) {
            return ResponseEntity.badRequest()
                    .body(ToggleResponse.error("Некорректный type"));
        }

        try {
            StrategySettings settings = resolveBaselineSettings(chatId, type);
            if (settings == null) {
                return ResponseEntity.badRequest()
                        .body(ToggleResponse.error("Настройки стратегии не найдены"));
            }

            log.info(
                    "🌐 [API] toggle strategy: chatId={}, type={}, exchange={}, network={}, symbol={}",
                    chatId,
                    type,
                    settings.getExchangeName(),
                    settings.getNetworkType(),
                    settings.getSymbol()
            );

            StrategyRunInfo info = webStrategyFacade.toggle(
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

        } catch (Exception ex) {
            log.error("❌ Ошибка переключения стратегии", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ToggleResponse.error("Ошибка переключения стратегии"));
        }
    }

    private StrategySettings resolveBaselineSettings(Long chatId, StrategyType type) {
        List<StrategySettings> all = strategySettingsService.findAllByChatId(chatId, null);
        if (all == null || all.isEmpty()) return null;

        return all.stream()
                .filter(s -> s != null && s.getType() == type)
                .sorted(Comparator
                        .comparing(StrategySettings::isActive).reversed()
                        .thenComparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed()
                        .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder())).reversed()
                )
                .findFirst()
                .orElse(null);
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
