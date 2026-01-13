package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.web.advanced.AdvancedRenderContext;
import com.chicu.aitradebot.web.advanced.StrategyAdvancedRegistry;
import com.chicu.aitradebot.web.advanced.StrategyAdvancedRenderer;
import com.chicu.aitradebot.web.dto.AdvancedTabDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/strategy/settings")
public class StrategySettingsAdvancedController {

    private final StrategySettingsService strategySettingsService;
    private final StrategyAdvancedRegistry advancedRegistry;

    private static Instant toInstant(LocalDateTime ts) {
        if (ts == null) return null;
        return ts.atZone(ZoneId.systemDefault()).toInstant();
    }

    @GetMapping("/advanced")
    public AdvancedTabDto getAdvanced(
            @RequestParam long chatId,
            @RequestParam StrategyType type,
            @RequestParam String exchange,
            @RequestParam NetworkType network
    ) {
        StrategySettings ss = strategySettingsService.getOrCreate(chatId, type, exchange, network);

        StrategyAdvancedRenderer renderer = advancedRegistry.get(type);

        AdvancedRenderContext ctx = AdvancedRenderContext.builder()
                .chatId(chatId)
                .strategyType(type)
                .exchange(exchange)
                .networkType(network)
                .controlMode(ss.getAdvancedControlMode())
                .params(Map.of())
                .build();

        String html = (renderer != null)
                ? renderer.render(ctx)
                : "<div class='alert alert-secondary mb-0'>Для стратегии нет advanced-блока.</div>";

        return new AdvancedTabDto(
                ss.isActive(),
                ss.getAdvancedControlMode(),
                ss.getMlConfidence(),
                ss.getTotalProfitPct(),

                // ✅ LocalDateTime -> Instant (фикс твоей ошибки компиляции)
                toInstant(ss.getUpdatedAt()),
                toInstant(ss.getStartedAt()),
                toInstant(ss.getStoppedAt()),

                ss.getAccountAsset(),
                ss.getSymbol(),
                ss.getTimeframe(),

                html,
                ctx.canSubmit()
        );
    }

    @PostMapping(value = "/advanced/submit", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String, Object> submitAdvanced(
            @RequestParam long chatId,
            @RequestParam StrategyType type,
            @RequestParam String exchange,
            @RequestParam NetworkType network,
            @RequestParam Map<String, String> allParams
    ) {
        StrategySettings ss = strategySettingsService.getOrCreate(chatId, type, exchange, network);

        StrategyAdvancedRenderer renderer = advancedRegistry.get(type);
        if (renderer == null) {
            return Map.of("ok", false, "message", "Нет renderer для стратегии " + type);
        }

        AdvancedRenderContext ctx = AdvancedRenderContext.builder()
                .chatId(chatId)
                .strategyType(type)
                .exchange(exchange)
                .networkType(network)
                .controlMode(ss.getAdvancedControlMode())
                .params(new HashMap<>(allParams))
                .build();

        if (!ctx.canSubmit()) {
            return Map.of("ok", false, "message", "Режим AI: ручные параметры запрещены");
        }

        renderer.handleSubmit(ctx);
        return Map.of("ok", true);
    }
}
