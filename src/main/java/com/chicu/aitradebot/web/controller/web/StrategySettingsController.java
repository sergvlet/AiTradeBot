package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.account.AccountBalanceService;
import com.chicu.aitradebot.account.AccountBalanceSnapshot;
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
import com.chicu.aitradebot.strategy.rsie.RsiEmaStrategySettings;
import com.chicu.aitradebot.strategy.rsie.RsiEmaStrategySettingsService;
import com.chicu.aitradebot.web.advanced.AdvancedRenderContext;
import com.chicu.aitradebot.web.advanced.StrategyAdvancedRegistry;
import com.chicu.aitradebot.web.advanced.StrategyAdvancedRenderer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
@RequestMapping("/strategies")
public class StrategySettingsController {

    private final StrategySettingsService strategySettingsService;
    private final ExchangeSettingsService exchangeSettingsService;
    private final RsiEmaStrategySettingsService rsiEmaSettingsService;
    private final StrategySettingsCache settingsCache;
    private final AccountBalanceService accountBalanceService;
    private final MarketSymbolService marketSymbolService;
    private final StrategyAdvancedRegistry strategyAdvancedRegistry;
    private final AiStrategyOrchestrator orchestrator;

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

        // =====================================================
        // StrategySettings (unified)
        // =====================================================
        StrategySettings strategy =
                strategySettingsService
                        .findLatest(chatId, strategyType, exchange, network)
                        .orElseGet(() -> strategySettingsService.getOrCreate(chatId, strategyType, exchange, network));

        // runtime status (active)
        try {
            StrategyRunInfo runtime = orchestrator.getStatus(chatId, strategyType, exchange, network);
            if (runtime != null) strategy.setActive(runtime.isActive());
        } catch (Exception e) {
            log.warn("⚠ Ошибка при получении статуса стратегии: {}", e.getMessage());
        }

        pullRsiEmaIntoUnifiedIfEmpty(strategyType, chatId, strategy);

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

        // ✅ важное: UI не должен показывать “старую диагностику”
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

        StrategySettings s =
                strategySettingsService
                        .findLatest(chatId, strategyType, exchange, network)
                        .orElseGet(() -> strategySettingsService.getOrCreate(chatId, strategyType, exchange, network));

        // global
        if (params.containsKey("advancedControlMode")) {
            try {
                s.setAdvancedControlMode(AdvancedControlMode.valueOf(params.get("advancedControlMode")));
            } catch (Exception e) {
                log.warn("Invalid advancedControlMode: {}", params.get("advancedControlMode"));
            }
        }

        String accountAsset = params.get("accountAsset");
        if (accountAsset != null && !accountAsset.isBlank()) s.setAccountAsset(accountAsset);

        switch (saveScope) {

            case "network" -> {
                s.setExchangeName(exchange);
                s.setNetworkType(network);
                strategySettingsService.save(s);

                // запись под ключи должна существовать
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
                strategySettingsService.updateRiskFromUi(
                        chatId, strategyType, exchange, network,
                        parseBigDecimalOrNull(params.get("dailyLossLimitPct")),
                        parseBigDecimalOrNull(params.get("riskPerTradePct"))
                );

                StrategySettings refreshed =
                        strategySettingsService.findLatest(chatId, strategyType, exchange, network).orElseThrow();

                BigDecimal stopLossPct = parseBigDecimalOrNull(params.get("stopLossPct"));
                BigDecimal takeProfitPct = parseBigDecimalOrNull(params.get("takeProfitPct"));

                boolean changed = false;
                if (stopLossPct != null) { refreshed.setStopLossPct(stopLossPct); changed = true; }
                if (takeProfitPct != null) { refreshed.setTakeProfitPct(takeProfitPct); changed = true; }

                if (changed) strategySettingsService.save(refreshed);
            }

            case "general" -> {
                s.setReinvestProfit(params.containsKey("reinvestProfit"));

                BigDecimal maxExposureUsd = parseBigDecimalOrNull(params.get("maxExposureUsd"));
                Integer maxExposurePct = parseIntOrNull(params.get("maxExposurePct"));

                if (maxExposureUsd != null && maxExposureUsd.signum() <= 0) maxExposureUsd = null;
                if (maxExposurePct != null && (maxExposurePct <= 0 || maxExposurePct > 100)) maxExposurePct = null;

                s.setMaxExposureUsd(maxExposureUsd);
                s.setMaxExposurePct(maxExposurePct);
                strategySettingsService.save(s);
            }

            case "advanced" -> {
                AdvancedControlMode currentMode = s.getAdvancedControlMode();

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
                        s.setAdvancedControlMode(AdvancedControlMode.valueOf(modeRaw));
                    } catch (IllegalArgumentException e) {
                        log.warn("⚠️ Invalid advancedControlMode='{}'", modeRaw);
                    }
                }

                strategySettingsService.save(s);
            }

            default -> log.warn("⚠️ Unknown saveScope='{}'", saveScope);
        }

        syncRsiEmaFromUnified(strategyType, chatId, s);
        settingsCache.invalidate(chatId, strategyType);

        String tab = params.getOrDefault("tab", "network");
        return buildRedirect(strategyType, chatId, exchange, network, tab);
    }

    // =====================================================
    // POST — DIAGNOSE (правильный путь для страницы настроек)
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
    // POST — DIAGNOSE (совместимость со старым JS)
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
            return StrategyType.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            log.error("❌ Invalid strategy type in path: '{}'", raw);
            throw e;
        }
    }

    private String normalizeExchange(String exchange) {
        return (exchange == null || exchange.isBlank())
                ? "BINANCE"
                : exchange.trim().toUpperCase();
    }

    private NetworkType parseNetworkOrDefault(String raw, NetworkType def) {
        if (raw == null || raw.isBlank()) return def;
        try {
            return NetworkType.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return def;
        }
    }

    // RSI EMA legacy
    private void pullRsiEmaIntoUnifiedIfEmpty(StrategyType type, long chatId, StrategySettings s) {
        if (type != StrategyType.RSI_EMA) return;
        if (s.getSymbol() != null && s.getTimeframe() != null) return;

        RsiEmaStrategySettings t = rsiEmaSettingsService.getOrCreate(chatId);
        s.setSymbol(t.getSymbol());
        s.setTimeframe(t.getTimeframe());
        s.setCachedCandlesLimit(t.getCachedCandlesLimit());
        s.setNetworkType(t.getNetworkType());

        strategySettingsService.save(s);
    }

    private void syncRsiEmaFromUnified(StrategyType type, long chatId, StrategySettings s) {
        if (type != StrategyType.RSI_EMA) return;

        RsiEmaStrategySettings t = rsiEmaSettingsService.getOrCreate(chatId);
        t.setSymbol(s.getSymbol());
        t.setTimeframe(s.getTimeframe());
        t.setCachedCandlesLimit(s.getCachedCandlesLimit());
        t.setNetworkType(s.getNetworkType());
        rsiEmaSettingsService.save(t);
    }

    private BigDecimal parseBigDecimalOrNull(String v) {
        try { return v == null ? null : new BigDecimal(v.trim()); }
        catch (Exception e) { return null; }
    }

    private Integer parseIntOrNull(String v) {
        try { return v == null ? null : Integer.parseInt(v.trim()); }
        catch (Exception e) { return null; }
    }
}
