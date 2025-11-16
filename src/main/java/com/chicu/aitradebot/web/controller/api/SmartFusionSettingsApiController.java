package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettings;
import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettingsService;
import com.chicu.aitradebot.strategy.smartfusion.dto.SmartFusionUserSettingsDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 🌐 REST API для работы с пользовательскими настройками Smart Fusion AI v3.0
 *  - GET /api/smartfusion/settings?chatId=123&symbol=BTCUSDT
 *  - PUT /api/smartfusion/settings?chatId=123
 */
@RestController
@RequestMapping("/api/smartfusion/settings")
@RequiredArgsConstructor
@Slf4j
public class SmartFusionSettingsApiController {

    private final SmartFusionStrategySettingsService settingsService;

    /**
     * 📥 Получение текущих настроек пользователя (или дефолтных, если нет в БД)
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getUserSettings(@RequestParam Long chatId,
                                             @RequestParam(defaultValue = "BTCUSDT") String symbol) {
        try {
            SmartFusionStrategySettings settings = settingsService.getOrCreate(chatId);
            log.info("📤 [GET] SmartFusion settings loaded (chatId={}, symbol={}, id={})",
                    chatId, symbol, settings != null ? settings.getId() : null);
            return ResponseEntity.ok(settings);
        } catch (Exception e) {
            log.error("❌ Ошибка при загрузке SmartFusion settings (chatId={}): {}", chatId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body("Ошибка загрузки настроек: " + e.getMessage());
        }
    }

    /**
     * 💾 Обновление настроек пользователя.
     */
    @PutMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> updateUserSettings(@RequestParam Long chatId,
                                                @RequestBody SmartFusionUserSettingsDto dto) {
        try {
            if (dto.getSymbol() == null || dto.getSymbol().isBlank()) {
                log.warn("⚠️ Поле symbol пустое при сохранении настроек (chatId={})", chatId);
                return ResponseEntity.badRequest().body("Поле symbol обязательно");
            }

            SmartFusionStrategySettings updated = settingsService.updateUserParams(chatId, dto);
            log.info("✅ [PUT] SmartFusion settings updated (id={}, chatId={}, symbol={})",
                    updated != null ? updated.getId() : null, chatId, dto.getSymbol());
            return ResponseEntity.ok(updated);

        } catch (Exception e) {
            log.error("❌ Ошибка при обновлении SmartFusion settings (chatId={}): {}", chatId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body("Ошибка обновления настроек: " + e.getMessage());
        }
    }
}
