package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.web.advanced.AdvancedRenderContext;
import com.chicu.aitradebot.web.advanced.StrategyAdvancedRegistry;
import com.chicu.aitradebot.web.advanced.StrategyAdvancedRenderer;
import com.chicu.aitradebot.web.dto.AdvancedTabDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/strategy/settings")
public class StrategySettingsAdvancedController {

    private final StrategySettingsService strategySettingsService;
    private final StrategyAdvancedRegistry advancedRegistry;

    private static String normalizeExchange(String exchange) {
        if (exchange == null) return "BINANCE";
        String ex = exchange.trim().toUpperCase(Locale.ROOT);
        return ex.isEmpty() ? "BINANCE" : ex;
    }

    private static Instant toInstant(LocalDateTime ts) {
        if (ts == null) return null;
        return ts.atZone(ZoneId.systemDefault()).toInstant();
    }

    private static AdvancedControlMode parseModeOrNull(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (v.isEmpty()) return null;
        try {
            return AdvancedControlMode.valueOf(v);
        } catch (Exception ignored) {
            return null;
        }
    }

    // =========================================================
    // GET /advanced
    // =========================================================
    @GetMapping("/advanced")
    public AdvancedTabDto getAdvanced(
            @RequestParam long chatId,
            @RequestParam StrategyType type,
            @RequestParam String exchange,
            @RequestParam NetworkType network
    ) {
        String ex = normalizeExchange(exchange);

        StrategySettings ss = strategySettingsService.getOrCreate(chatId, type, ex, network);

        StrategyAdvancedRenderer renderer = advancedRegistry.get(type);

        AdvancedControlMode mode = ss.getAdvancedControlMode() != null
                ? ss.getAdvancedControlMode()
                : AdvancedControlMode.MANUAL;

        AdvancedRenderContext ctx = AdvancedRenderContext.builder()
                .chatId(chatId)
                .strategyType(type)
                .exchange(ex)
                .networkType(network)
                .controlMode(mode)
                .params(Map.of())
                .build();

        String html = (renderer != null)
                ? renderer.render(ctx)
                : "<div class='text-secondary small'>Нет данных</div>";

        return new AdvancedTabDto(
                ss.isActive(),
                mode,
                ss.getMlConfidence(),
                ss.getTotalProfitPct(),

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

    // =========================================================
    // POST /advanced/submit
    // ✅ теперь также сохраняет advancedControlMode в StrategySettings
    // =========================================================
    @Transactional
    @PostMapping(value = "/advanced/submit", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String, Object> submitAdvanced(
            @RequestParam long chatId,
            @RequestParam StrategyType type,
            @RequestParam String exchange,
            @RequestParam NetworkType network,
            @RequestParam Map<String, String> allParams
    ) {
        String ex = normalizeExchange(exchange);

        StrategySettings ss = strategySettingsService.getOrCreate(chatId, type, ex, network);

        // ✅ 1) сохраняем режим, если пришёл (accept: advancedControlMode / controlMode)
        AdvancedControlMode requestedMode =
                parseModeOrNull(allParams.get("advancedControlMode"));

        if (requestedMode == null) {
            requestedMode = parseModeOrNull(allParams.get("controlMode"));
        }

        if (requestedMode != null && requestedMode != ss.getAdvancedControlMode()) {
            ss.setAdvancedControlMode(requestedMode);
            strategySettingsService.save(ss);
        }

        // текущий режим после возможного сохранения
        AdvancedControlMode mode = ss.getAdvancedControlMode() != null
                ? ss.getAdvancedControlMode()
                : AdvancedControlMode.MANUAL;

        StrategyAdvancedRenderer renderer = advancedRegistry.get(type);
        if (renderer == null) {
            return Map.of("ok", false, "message", "Нет renderer для стратегии " + type);
        }

        // чистим системные поля (и режим тоже не отдаём в params рендерера)
        HashMap<String, String> clean = new HashMap<>(allParams);
        clean.remove("chatId");
        clean.remove("type");
        clean.remove("exchange");
        clean.remove("network");
        clean.remove("advancedControlMode");
        clean.remove("controlMode");

        AdvancedRenderContext ctx = AdvancedRenderContext.builder()
                .chatId(chatId)
                .strategyType(type)
                .exchange(ex)
                .networkType(network)
                .controlMode(mode)
                .params(clean)
                .build();

        if (!ctx.canSubmit()) {
            return Map.of("ok", false, "message", "Режим AI: ручные параметры запрещены");
        }

        renderer.handleSubmit(ctx);
        return Map.of("ok", true);
    }
}
