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

import java.math.BigDecimal;
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

    private static final String PHASE_PAPER = "PAPER";
    private static final String PHASE_LIVE = "LIVE";
    private static final BigDecimal DEFAULT_GATE_MIN_PROB = new BigDecimal("0.550000");

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

    private String resolveExchange(String raw, StrategySettings ss) {
        if (raw != null && !raw.isBlank()) {
            return normalizeExchange(raw);
        }
        if (ss != null && ss.getExchangeName() != null && !ss.getExchangeName().isBlank()) {
            return normalizeExchange(ss.getExchangeName());
        }
        return "BINANCE";
    }

    private NetworkType resolveNetwork(NetworkType raw, StrategySettings ss) {
        if (raw != null) {
            return raw;
        }
        if (ss != null && ss.getNetworkType() != null) {
            return ss.getNetworkType();
        }
        return NetworkType.TESTNET;
    }

    private boolean syncContextIfNeeded(StrategySettings ss, String exchange, NetworkType network) {
        if (ss == null) return false;

        boolean changed = false;

        String ex = normalizeExchange(exchange);
        if (ss.getExchangeName() == null || !ss.getExchangeName().equalsIgnoreCase(ex)) {
            ss.setExchangeName(ex);
            changed = true;
        }

        NetworkType net = (network != null ? network : NetworkType.TESTNET);
        if (ss.getNetworkType() != net) {
            ss.setNetworkType(net);
            changed = true;
        }

        return changed;
    }

    private static void enforceModeRules(StrategySettings ss, AdvancedControlMode mode, NetworkType network) {
        if (ss == null || mode == null) return;

        NetworkType net = (network != null ? network : NetworkType.TESTNET);
        String safePhase = (net == NetworkType.TESTNET ? PHASE_PAPER : PHASE_LIVE);

        switch (mode) {
            case MANUAL -> {
                ss.setAutoTuneEnabled(false);
                ss.setMlGateEnabled(false);
                ss.setRunPhase(PHASE_LIVE);

                ss.setGateMinProb(null);
                ss.setMlModelKey(null);
                ss.setMlSchemaHash(null);
                ss.setMlModelVersion(null);
            }
            case HYBRID -> {
                ss.setAutoTuneEnabled(false);
                ss.setMlGateEnabled(true);
                if (ss.getGateMinProb() == null) {
                    ss.setGateMinProb(DEFAULT_GATE_MIN_PROB);
                }
                ss.setRunPhase(safePhase);
            }
            case AI -> {
                ss.setAutoTuneEnabled(true);
                if (!ss.isMlGateEnabled()) {
                    ss.setMlGateEnabled(true);
                }
                if (ss.getGateMinProb() == null && ss.isMlGateEnabled()) {
                    ss.setGateMinProb(DEFAULT_GATE_MIN_PROB);
                }
                ss.setRunPhase(safePhase);
            }
        }
    }

    @GetMapping("/advanced")
    public AdvancedTabDto getAdvanced(
            @RequestParam long chatId,
            @RequestParam StrategyType type,
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) NetworkType network
    ) {
        StrategySettings ss = strategySettingsService.getOrCreate(chatId, type);

        String ex = resolveExchange(exchange, ss);
        NetworkType net = resolveNetwork(network, ss);

        boolean ctxChanged = syncContextIfNeeded(ss, ex, net);
        if (ctxChanged) {
            strategySettingsService.save(ss);
        }

        StrategyAdvancedRenderer renderer = advancedRegistry.get(type);

        AdvancedControlMode mode = (ss.getAdvancedControlMode() != null)
                ? ss.getAdvancedControlMode()
                : AdvancedControlMode.MANUAL;

        AdvancedRenderContext ctx = AdvancedRenderContext.builder()
                .chatId(chatId)
                .strategyType(type)
                .exchange(ex)
                .networkType(net)
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

    @Transactional
    @PostMapping(value = "/advanced/submit", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String, Object> submitAdvanced(
            @RequestParam long chatId,
            @RequestParam StrategyType type,
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) NetworkType network,
            @RequestParam Map<String, String> allParams
    ) {
        StrategySettings ss = strategySettingsService.getOrCreate(chatId, type);

        String ex = resolveExchange(exchange, ss);
        NetworkType net = resolveNetwork(network, ss);

        boolean dirty = syncContextIfNeeded(ss, ex, net);

        AdvancedControlMode requestedMode = parseModeOrNull(allParams.get("advancedControlMode"));
        if (requestedMode == null) {
            requestedMode = parseModeOrNull(allParams.get("controlMode"));
        }

        if (requestedMode != null && requestedMode != ss.getAdvancedControlMode()) {
            ss.setAdvancedControlMode(requestedMode);
            enforceModeRules(ss, requestedMode, net);
            dirty = true;
        }

        AdvancedControlMode mode = (ss.getAdvancedControlMode() != null)
                ? ss.getAdvancedControlMode()
                : AdvancedControlMode.MANUAL;

        StrategyAdvancedRenderer renderer = advancedRegistry.get(type);
        if (renderer == null) {
            return Map.of("ok", false, "message", "Нет renderer для стратегии " + type);
        }

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
                .networkType(net)
                .controlMode(mode)
                .params(clean)
                .build();

        if (!ctx.canSubmit()) {
            return Map.of("ok", false, "message", "Режим AI: ручные параметры запрещены");
        }

        renderer.handleSubmit(ctx);

        if (dirty) {
            strategySettingsService.save(ss);
        }

        return Map.of(
                "ok", true,
                "mode", mode.name(),
                "canSubmit", ctx.canSubmit()
        );
    }

    public record ApplyModeRequest(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network,
            String advancedControlMode,
            String reason
    ) {}

    @Transactional
    @PostMapping(value = "/apply", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> applyMode(@RequestBody ApplyModeRequest req) {
        StrategySettings ss = strategySettingsService.getOrCreate(req.chatId(), req.type());

        String ex = resolveExchange(req.exchange(), ss);
        NetworkType net = resolveNetwork(req.network(), ss);

        boolean dirty = syncContextIfNeeded(ss, ex, net);

        AdvancedControlMode requested = parseModeOrNull(req.advancedControlMode());
        if (requested == null) {
            return Map.of("applied", false, "reason", "invalid_mode");
        }

        boolean changedMode = (ss.getAdvancedControlMode() != requested);
        ss.setAdvancedControlMode(requested);
        enforceModeRules(ss, requested, net);

        strategySettingsService.save(ss);

        return Map.of(
                "applied", true,
                "changed", (dirty || changedMode),
                "mode", requested.name(),
                "runPhase", ss.getRunPhase(),
                "autoTuneEnabled", ss.isAutoTuneEnabled(),
                "mlGateEnabled", ss.isMlGateEnabled(),
                "reason", (req.reason() != null ? req.reason() : "apply")
        );
    }
}
