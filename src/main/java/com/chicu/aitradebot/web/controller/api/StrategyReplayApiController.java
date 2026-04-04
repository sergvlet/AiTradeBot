package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.web.dto.StrategyChartDto;
import com.chicu.aitradebot.web.ui.UiStrategyLayerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
public class StrategyReplayApiController {

    private final SimpMessagingTemplate ws;
    private final UiStrategyLayerService uiLayers;
    private final StrategySettingsService settingsService;

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

        StrategyChartDto.Layers layers = uiLayers.buildLatestLayersForSnapshot(chatId, type, symbol);
        if (layers == null || isEmpty(layers)) {
            return ResponseEntity.ok().build();
        }

        String dest = "/topic/strategy/" + chatId + "/" + type.name();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "layers");
        msg.put("chatId", chatId);
        msg.put("strategyType", type.name());
        msg.put("symbol", symbol);
        msg.put("time", Instant.now().toEpochMilli());
        msg.put("layers", layers);

        ws.convertAndSend(dest, msg);

        if (log.isDebugEnabled()) {
            log.debug("🔁 [WEB] replay send layers chatId={} strategy={} symbol={}", chatId, type, symbol);
        }

        return ResponseEntity.ok().build();
    }

    private boolean isEmpty(StrategyChartDto.Layers layers) {
        return (layers.getLevels() == null || layers.getLevels().isEmpty())
                && layers.getZone() == null
                && layers.getTpSl() == null
                && layers.getWindowZone() == null
                && (layers.getPriceLines() == null || layers.getPriceLines().isEmpty())
                && (layers.getTrades() == null || layers.getTrades().isEmpty());
    }

    private static String normSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }
}
