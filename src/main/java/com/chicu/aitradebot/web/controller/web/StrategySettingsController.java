package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.account.AccountBalanceService;
import com.chicu.aitradebot.account.AccountBalanceSnapshot;
import com.chicu.aitradebot.ai.tuning.AutoTunerOrchestrator;
import com.chicu.aitradebot.ai.tuning.TuningRequest;
import com.chicu.aitradebot.ai.tuning.TuningResult;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.exchange.model.AccountFees;
import com.chicu.aitradebot.exchange.model.ApiKeyDiagnostics;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import com.chicu.aitradebot.market.model.SymbolDescriptor;
import com.chicu.aitradebot.market.service.MarketSymbolService;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.cache.StrategySettingsCache;
import com.chicu.aitradebot.web.advanced.AdvancedRenderContext;
import com.chicu.aitradebot.web.advanced.StrategyAdvancedRegistry;
import com.chicu.aitradebot.web.advanced.StrategyAdvancedRenderer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
@RequestMapping("/strategies")
public class StrategySettingsController {

    private final StrategySettingsService strategySettingsService;
    private final ExchangeSettingsService exchangeSettingsService;
    private final StrategySettingsCache settingsCache;
    private final AccountBalanceService accountBalanceService;
    private final MarketSymbolService marketSymbolService;
    private final StrategyAdvancedRegistry strategyAdvancedRegistry;
    private final AiStrategyOrchestrator orchestrator;
    private final AutoTunerOrchestrator autoTuner;

    private static final List<String> DEFAULT_TIMEFRAMES = List.of(
            "1s", "5s", "15s", "1m", "3m", "5m", "15m", "30m", "1h", "4h", "1d"
    );

    private static final List<String> AVAILABLE_EXCHANGES =
            List.of("BINANCE", "BYBIT", "OKX");

    // =====================================================
    // GET — ОТКРЫТЬ НАСТРОЙКИ
    // =====================================================
    @GetMapping("/{type}/config")
    public String openSettings(
            @PathVariable("type") String typeRaw,
            @RequestParam("chatId") long chatId,
            @RequestParam(value = "tab", required = false) String tab,
            @RequestParam(value = "exchange", required = false) String exchangeParam,
            @RequestParam(value = "network", required = false) String networkParam,
            HttpServletRequest request,
            Model model
    ) {
        StrategyType strategyType = parseStrategyType(typeRaw);

        String exchange = normalizeExchange(exchangeParam);
        NetworkType network = parseNetworkOrDefault(networkParam, NetworkType.TESTNET);

        // ✅ В ПРОДЕ: НЕ используем "latest", всегда работаем строго по контексту
        StrategySettings strategy =
                strategySettingsService.getOrCreate(chatId, strategyType, exchange, network);

        // runtime status (active)
        try {
            StrategyRunInfo runtime = orchestrator.getStatus(chatId, strategyType, exchange, network);
            if (runtime != null) strategy.setActive(runtime.isActive());
        } catch (Exception e) {
            log.warn("⚠ Ошибка при получении статуса стратегии: {}", e.getMessage());
        }

        // =====================================================
        // BALANCE
        // =====================================================
        AccountBalanceSnapshot balance =
                accountBalanceService.getSnapshot(chatId, strategyType, exchange, network);

        // =====================================================
        // Exchange settings (keys) + Diagnostics
        // =====================================================
        ExchangeSettings exchangeSettings = exchangeSettingsService.getOrCreate(chatId, exchange, network);

        boolean diagnosticsSupported = isDiagnosticsSupported(exchange);

        ApiKeyDiagnostics diagnostics = null;
        if (diagnosticsSupported && exchangeSettings.hasBaseKeys()) {
            diagnostics = exchangeSettingsService.testConnectionDetailed(exchangeSettings);
        }

        boolean connectionOk = diagnostics != null && diagnostics.isOk();

        // selected asset
        String selectedAsset = strategy.getAccountAsset();
        if (selectedAsset == null || selectedAsset.isBlank()) {
            selectedAsset = balance.getSelectedAsset();
        }

        // symbol info
        SymbolDescriptor symbolInfo = null;
        if (strategy.getSymbol() != null && !strategy.getSymbol().isBlank()) {
            try {
                symbolInfo = marketSymbolService.getSymbolInfo(
                        exchange, network, selectedAsset, strategy.getSymbol()
                );
            } catch (Exception e) {
                log.warn("⚠ Не удалось получить symbolInfo symbol={}: {}", strategy.getSymbol(), e.getMessage());
            }
        }

        // fees — только если ключи есть и диагностика успешна
        AccountFees accountFees = null;
        if (diagnosticsSupported && exchangeSettings.hasBaseKeys() && connectionOk) {
            try {
                accountFees = accountBalanceService.getAccountFees(chatId, exchange, network);
            } catch (Exception e) {
                log.warn("⚠ Не удалось получить комиссии: {}", e.getMessage());
            }
        }

        // advanced html
        String strategyAdvancedHtml = null;
        StrategyAdvancedRenderer advancedRenderer = strategyAdvancedRegistry.get(strategyType);
        if (advancedRenderer != null) {
            strategyAdvancedHtml = advancedRenderer.render(
                    AdvancedRenderContext.builder()
                            .chatId(chatId)
                            .strategyType(strategyType)
                            .exchange(exchange)
                            .networkType(network)
                            .controlMode(strategy.getAdvancedControlMode())
                            .build()
            );
        }

        // =====================================================
        // model
        // =====================================================
        model.addAttribute("page", "strategies/settings");
        model.addAttribute("chatId", chatId);
        model.addAttribute("type", strategyType);
        model.addAttribute("strategy", strategy);

        model.addAttribute("activeTab", tab != null ? tab : "network");
        model.addAttribute("availableExchanges", AVAILABLE_EXCHANGES);
        model.addAttribute("availableTimeframes", DEFAULT_TIMEFRAMES);

        model.addAttribute("selectedExchange", exchange);
        model.addAttribute("selectedNetwork", network);

        model.addAttribute("exchangeSettings", exchangeSettings);

        model.addAttribute("diagnosticsSupported", diagnosticsSupported);
        model.addAttribute("diagnostics", diagnosticsSupported ? diagnostics : null);
        model.addAttribute("connectionOk", diagnosticsSupported && connectionOk);

        model.addAttribute("availableAssets", balance.getAvailableAssets());
        model.addAttribute("selectedAsset", selectedAsset);
        model.addAttribute("availableBalance", balance.getSelectedFreeBalance());
        model.addAttribute("balanceConnectionOk", balance.isConnectionOk());

        model.addAttribute("accountFees", accountFees);
        model.addAttribute("symbolInfo", symbolInfo);

        model.addAttribute("strategyAdvancedHtml", strategyAdvancedHtml);

        return "layout/app";
    }

    // =====================================================
    // POST — СОХРАНЕНИЕ
    // =====================================================
    @PostMapping("/{type}/config")
    public String saveSettings(
            @PathVariable("type") String typeRaw,
            @RequestParam("chatId") long chatId,
            @RequestParam("saveScope") String saveScope,
            @RequestParam Map<String, String> params,
            @ModelAttribute("strategy") StrategySettings form
    ) {
        StrategyType strategyType = parseStrategyType(typeRaw);

        String exchange = normalizeExchange(params.get("exchange"));
        NetworkType network = parseNetworkOrDefault(params.get("network"), NetworkType.TESTNET);

        // ✅ В ПРОДЕ: НЕ ищем latest — строго по ключу
        StrategySettings s = strategySettingsService.getOrCreate(chatId, strategyType, exchange, network);

        // global: режим можно менять из любой вкладки — сохраняем всегда
        if (params.containsKey("advancedControlMode")) {
            try {
                s.setAdvancedControlMode(AdvancedControlMode.valueOf(params.get("advancedControlMode").trim().toUpperCase(Locale.ROOT)));
            } catch (Exception e) {
                log.warn("Invalid advancedControlMode: {}", params.get("advancedControlMode"));
            }
        }

        // asset (если прислали)
        String accountAsset = params.get("accountAsset");
        if (accountAsset != null && !accountAsset.isBlank()) {
            s.setAccountAsset(accountAsset.trim().toUpperCase(Locale.ROOT));
        }

        switch (saveScope) {

            /**
             * ✅ ВАЖНО:
             * вкладка "Сеть" — это выбор КОНТЕКСТА (exchange/network),
             * а не “переписать ключ” существующей строки StrategySettings.
             *
             * Поэтому НЕ делаем:
             *   s.setExchangeName(exchange);
             *   s.setNetworkType(network);
             *
             * Мы просто гарантируем наличие строк под новый контекст и редиректим.
             */
            case "network" -> {
                strategySettingsService.getOrCreate(chatId, strategyType, exchange, network);
                exchangeSettingsService.getOrCreate(chatId, exchange, network);
            }

            case "keys" -> {
                exchangeSettingsService.saveKeys(
                        chatId,
                        exchange,
                        network,
                        params.get("apiKey"),
                        params.get("apiSecret"),
                        params.get("passphrase"),
                        params.get("subAccount")
                );
            }

            case "trade" -> {
                s.setSymbol(form.getSymbol());
                s.setTimeframe(form.getTimeframe());
                s.setCachedCandlesLimit(form.getCachedCandlesLimit());

                Integer maxOpenOrders = parseIntOrNull(params.get("maxOpenOrders"));
                Integer cooldownSeconds = parseIntOrNull(params.get("cooldownSeconds"));

                s.setMaxOpenOrders((maxOpenOrders != null && maxOpenOrders > 0) ? maxOpenOrders : null);
                s.setCooldownSeconds((cooldownSeconds != null && cooldownSeconds > 0) ? cooldownSeconds : null);

                strategySettingsService.save(s);
            }

            case "risk" -> {
                s.setRiskPerTradePct(validatePct(parseBigDecimalOrNull(params.get("riskPerTradePct"))));
                s.setMinRiskReward(validatePositiveOrNull(parseBigDecimalOrNull(params.get("minRiskReward"))));

                Integer leverage = parseIntOrNull(params.get("leverage"));
                if (leverage != null && leverage >= 1) s.setLeverage(leverage);

                s.setMaxDrawdownPct(validatePct(parseBigDecimalOrNull(params.get("maxDrawdownPct"))));
                s.setMaxDrawdownUsd(validateMoneyOrNull(parseBigDecimalOrNull(params.get("maxDrawdownUsd"))));

                s.setMaxPositionPct(validatePct(parseBigDecimalOrNull(params.get("maxPositionPct"))));
                s.setMaxPositionUsd(validateMoneyOrNull(parseBigDecimalOrNull(params.get("maxPositionUsd"))));

                Integer maxTradesPerDay = parseIntOrNull(params.get("maxTradesPerDay"));
                s.setMaxTradesPerDay((maxTradesPerDay != null && maxTradesPerDay > 0) ? maxTradesPerDay : null);

                Integer maxConsecutiveLosses = parseIntOrNull(params.get("maxConsecutiveLosses"));
                s.setMaxConsecutiveLosses((maxConsecutiveLosses != null && maxConsecutiveLosses > 0) ? maxConsecutiveLosses : null);

                Integer cooldownAfterLossSeconds = parseIntOrNull(params.get("cooldownAfterLossSeconds"));
                s.setCooldownAfterLossSeconds((cooldownAfterLossSeconds != null && cooldownAfterLossSeconds > 0) ? cooldownAfterLossSeconds : null);

                // checkbox
                s.setAllowAveraging(params.containsKey("allowAveraging"));

                Integer cooldownSeconds = parseIntOrNull(params.get("cooldownSeconds"));
                s.setCooldownSeconds((cooldownSeconds != null && cooldownSeconds > 0) ? cooldownSeconds : null);

                Integer maxOpenOrders = parseIntOrNull(params.get("maxOpenOrders"));
                s.setMaxOpenOrders((maxOpenOrders != null && maxOpenOrders > 0) ? maxOpenOrders : null);

                strategySettingsService.save(s);
            }

            case "general" -> {
                s.setReinvestProfit(params.containsKey("reinvestProfit"));

                s.setDailyLossLimitPct(validatePct(parseBigDecimalOrNull(params.get("dailyLossLimitPct"))));

                BigDecimal maxExposureUsd = validateMoneyOrNull(parseBigDecimalOrNull(params.get("maxExposureUsd")));
                BigDecimal maxExposurePct = validatePct(parseBigDecimalOrNull(params.get("maxExposurePct")));

                s.setMaxExposureUsd(maxExposureUsd);
                s.setMaxExposurePct(maxExposurePct);

                strategySettingsService.save(s);
            }

            case "advanced" -> {
                AdvancedControlMode currentMode = s.getAdvancedControlMode();
                if (currentMode == null) currentMode = AdvancedControlMode.MANUAL;

                StrategyAdvancedRenderer renderer = strategyAdvancedRegistry.get(strategyType);
                if (renderer != null && currentMode != AdvancedControlMode.AI) {
                    AdvancedRenderContext ctx =
                            AdvancedRenderContext.builder()
                                    .chatId(chatId)
                                    .strategyType(strategyType)
                                    .exchange(exchange)
                                    .networkType(network)
                                    .controlMode(currentMode)
                                    .params(params)
                                    .build();
                    renderer.handleSubmit(ctx);
                }

                String modeRaw = params.get("advancedControlMode");
                if (modeRaw != null) {
                    try {
                        s.setAdvancedControlMode(AdvancedControlMode.valueOf(modeRaw.trim().toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        log.warn("⚠️ Invalid advancedControlMode='{}'", modeRaw);
                    }
                }

                strategySettingsService.save(s);
            }

            default -> log.warn("⚠️ Unknown saveScope='{}'", saveScope);
        }

        settingsCache.invalidate(chatId, strategyType);

        String tab = params.getOrDefault("tab", "network");
        return buildRedirect(strategyType, chatId, exchange, network, tab);
    }

    // =========================================================
    // ✅ 4) POST /apply — применить HYBRID/AI (тюнинг) сразу
    // =========================================================
    @PostMapping("/apply")
    public ResponseEntity<ApplyResponse> apply(@RequestBody ApplyRequest req) {

        String ex = normalizeExchange(req.getExchange());
        NetworkType net = (req.getNetwork() != null) ? req.getNetwork() : NetworkType.TESTNET;

        StrategySettings s = strategySettingsService.getOrCreate(
                req.getChatId(),
                req.getType(),
                ex,
                net
        );

        // если режим пришёл с фронта — применим сразу
        if (req.getAdvancedControlMode() != null) {
            try {
                s.setAdvancedControlMode(AdvancedControlMode.valueOf(req.getAdvancedControlMode().trim().toUpperCase(Locale.ROOT)));
                strategySettingsService.save(s);
            } catch (Exception ignored) {}
        }

        AdvancedControlMode mode = s.getAdvancedControlMode();
        if (mode == null) mode = AdvancedControlMode.MANUAL;

        // MANUAL — просто подтверждаем, без AI
        if (mode == AdvancedControlMode.MANUAL) {
            return ResponseEntity.ok(
                    ApplyResponse.builder()
                            .ok(true)
                            .mode(mode)
                            .applied(false)
                            .reason("MANUAL: apply не требуется")
                            .build()
            );
        }

        // HYBRID/AI — запускаем тюнер
        TuningResult result;
        try {
            TuningRequest tr = TuningRequest.builder()
                    .chatId(req.getChatId())
                    .strategyType(req.getType())
                    .exchange(ex)
                    .network(net)
                    .symbol(s.getSymbol())
                    .timeframe(s.getTimeframe())
                    .candlesLimit(s.getCachedCandlesLimit())
                    .reason((req.getReason() == null || req.getReason().isBlank()) ? "ui_control_mode_change" : req.getReason())
                    .build();

            result = autoTuner.tune(tr);
        } catch (Exception e) {
            log.error("apply failed chatId={} type={} ex={} net={}: {}",
                    req.getChatId(), req.getType(), ex, net, e.getMessage(), e);

            return ResponseEntity.ok(
                    ApplyResponse.builder()
                            .ok(false)
                            .mode(mode)
                            .applied(false)
                            .reason("apply failed: " + e.getMessage())
                            .build()
            );
        }

        boolean applied = result != null && result.applied();
        String reason = result != null ? result.reason() : "null";

        return ResponseEntity.ok(
                ApplyResponse.builder()
                        .ok(true)
                        .mode(mode)
                        .applied(applied)
                        .reason(reason)
                        .build()
        );
    }

    @Data
    public static class ApplyRequest {
        private Long chatId;
        private StrategyType type;
        private String exchange;
        private NetworkType network;
        private String advancedControlMode;
        private String reason;
    }

    @Data
    @lombok.Builder
    public static class ApplyResponse {
        private boolean ok;
        private AdvancedControlMode mode;
        private boolean applied;
        private String reason;
    }

    // =====================================================
    // POST — DIAGNOSE
    // =====================================================
    @PostMapping("/{type}/config/diagnose")
    @ResponseBody
    public ApiKeyDiagnostics diagnose(
            @PathVariable("type") String typeRaw,
            @RequestParam("chatId") long chatId,
            @RequestParam("exchange") String exchange,
            @RequestParam("network") String network
    ) {
        parseStrategyType(typeRaw);

        String ex = normalizeExchange(exchange);
        NetworkType net = parseNetworkOrDefault(network, NetworkType.TESTNET);

        if (!isDiagnosticsSupported(ex)) {
            return ApiKeyDiagnostics.builder()
                    .ok(false)
                    .exchange(ex)
                    .message("Диагностика не поддерживается для биржи: " + ex)
                    .build();
        }

        ExchangeSettings s = exchangeSettingsService.getOrCreate(chatId, ex, net);

        if (s == null || !s.hasBaseKeys()) {
            return ApiKeyDiagnostics.notConfigured(ex, "Ключи не заданы");
        }

        return exchangeSettingsService.testConnectionDetailed(s);
    }

    // =====================================================
    // POST — DIAGNOSE (legacy)
    // =====================================================
    @PostMapping("/network/diagnose")
    @ResponseBody
    public ApiKeyDiagnostics diagnoseLegacy(
            @RequestParam("chatId") long chatId,
            @RequestParam("exchange") String exchange,
            @RequestParam("network") String network
    ) {
        String ex = normalizeExchange(exchange);
        NetworkType net = parseNetworkOrDefault(network, NetworkType.TESTNET);

        if (!isDiagnosticsSupported(ex)) {
            return ApiKeyDiagnostics.builder()
                    .ok(false)
                    .exchange(ex)
                    .message("Диагностика не поддерживается для биржи: " + ex)
                    .build();
        }

        ExchangeSettings s = exchangeSettingsService.getOrCreate(chatId, ex, net);

        if (s == null || !s.hasBaseKeys()) {
            return ApiKeyDiagnostics.notConfigured(ex, "Ключи не заданы");
        }

        return exchangeSettingsService.testConnectionDetailed(s);
    }

    // =====================================================
    // helpers
    // =====================================================

    private boolean isDiagnosticsSupported(String exchange) {
        String ex = normalizeExchange(exchange);
        return "BINANCE".equals(ex) || "BYBIT".equals(ex);
    }

    private String buildRedirect(StrategyType type, long chatId, String exchange, NetworkType network, String tab) {
        return "redirect:/strategies/" + type.name() +
               "/config?chatId=" + chatId +
               "&exchange=" + normalizeExchange(exchange) +
               "&network=" + network.name() +
               "&tab=" + tab;
    }

    private StrategyType parseStrategyType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Strategy type is blank");
        }
        try {
            return StrategyType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            log.error("❌ Invalid strategy type in path: '{}'", raw);
            throw e;
        }
    }

    private String normalizeExchange(String exchange) {
        return (exchange == null || exchange.isBlank())
                ? "BINANCE"
                : exchange.trim().toUpperCase(Locale.ROOT);
    }

    private NetworkType parseNetworkOrDefault(String raw, NetworkType def) {
        if (raw == null || raw.isBlank()) return def;
        try {
            return NetworkType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return def;
        }
    }

    private BigDecimal parseBigDecimalOrNull(String v) {
        try {
            if (v == null) return null;
            String s = v.trim().replace(",", ".");
            if (s.isEmpty()) return null;
            return new BigDecimal(s);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseIntOrNull(String v) {
        try { return v == null ? null : Integer.parseInt(v.trim()); }
        catch (Exception e) { return null; }
    }

    private BigDecimal validatePct(BigDecimal v) {
        if (v == null) return null;

        if (v.compareTo(BigDecimal.ZERO) < 0) v = BigDecimal.ZERO;
        if (v.compareTo(BigDecimal.valueOf(100)) > 0) v = BigDecimal.valueOf(100);

        return v.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal validateMoneyOrNull(BigDecimal v) {
        if (v == null) return null;
        if (v.compareTo(BigDecimal.ZERO) <= 0) return null;
        return v.setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal validatePositiveOrNull(BigDecimal v) {
        if (v == null) return null;
        if (v.compareTo(BigDecimal.ZERO) <= 0) return null;
        return v.setScale(6, RoundingMode.HALF_UP);
    }
}
