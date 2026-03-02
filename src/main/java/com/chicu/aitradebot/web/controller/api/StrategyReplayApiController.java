// src/main/java/com/chicu/aitradebot/web/controller/api/StrategyReplayApiController.java
package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.web.ui.UiStrategyLayerService;
import com.chicu.aitradebot.web.ui.entity.UiStrategyLayerEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
public class StrategyReplayApiController {

    private final SimpMessagingTemplate ws;
    private final UiStrategyLayerService uiLayers;
    private final StrategySettingsService settingsService;
    private final ObjectMapper objectMapper;

    @PostMapping("/{chatId}/{type}/replay")
    public ResponseEntity<Void> replay(@PathVariable("chatId") long chatId,
                                       @PathVariable("type") StrategyType type,
                                       @RequestParam(name = "symbol", required = false) String symbolOverride) {

        log.info("🔁 [WEB] replay request: chatId={}, type={}", chatId, type);
        if (chatId <= 0 || type == null) return ResponseEntity.ok().build();

        StrategySettings s;
        try {
            s = settingsService.getOrCreate(chatId, type);
        } catch (Exception e) {
            log.warn("⚠ [WEB] replay: cannot load settings chatId={} type={} err={}", chatId, type, e.getMessage());
            return ResponseEntity.ok().build();
        }

        String symbol = normSymbol(symbolOverride);
        if (symbol == null) symbol = normSymbol(s != null ? s.getSymbol() : null);
        if (symbol == null) return ResponseEntity.ok().build();

        String dest = "/topic/strategy/" + chatId + "/" + type.name();

        Object zone   = findAndParse(chatId, type, symbol, UiStrategyLayerService.TYPE_ZONE);
        Object levels = findAndParse(chatId, type, symbol, UiStrategyLayerService.TYPE_LEVELS);
        Object tpSl   = findAndParse(chatId, type, symbol, UiStrategyLayerService.TYPE_TP_SL);

        if (zone == null && levels == null && tpSl == null) {
            return ResponseEntity.ok().build();
        }

        Map<String, Object> layers = new LinkedHashMap<>();
        if (zone != null) layers.put("zone", zone);
        if (levels != null) layers.put("levels", levels);
        if (tpSl != null) layers.put("tpSl", tpSl);

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "layers");
        msg.put("chatId", chatId);
        msg.put("strategyType", type.name());
        msg.put("symbol", symbol);
        msg.put("time", Instant.now().toEpochMilli());
        msg.put("layers", layers);

        ws.convertAndSend(dest, msg);

        if (log.isDebugEnabled()) {
            log.debug("🔁 [WEB] replay send layers chatId={} strategy={} symbol={} keys={}",
                    chatId, type, symbol, layers.keySet());
        }

        return ResponseEntity.ok().build();
    }

    private Object findAndParse(long chatId, StrategyType type, String symbol, String layerType) {
        try {
            Optional<UiStrategyLayerEntity> opt = uiLayers.findLatestByType(chatId, type, symbol, layerType);
            if (opt.isEmpty()) return null;
            String json = opt.get().getPayload();
            return parseJson(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String normSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }
}