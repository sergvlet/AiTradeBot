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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
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
            "1s","5s","15s","1m","3m","5m","15m","30m","1h","4h","1d"
    );

    private static final List<String> AVAILABLE_EXCHANGES = List.of("BINANCE","BYBIT","OKX");

    // runPhase (единая точка истины)
    private static final String PHASE_LIVE     = "LIVE";
    private static final String PHASE_PAPER    = "PAPER";
    private static final String PHASE_BACKTEST = "BACKTEST";
    private static final String PHASE_COLLECT  = "COLLECT";

    // =====================================================
    // GET — ОТКРЫТЬ НАСТРОЙКИ
    // =====================================================
    @GetMapping("/{type}/config")
    @Transactional
    public String openSettings(
            @PathVariable("type") String typeRaw,
            @RequestParam("chatId") long chatId,
            @RequestParam(value = "exchange", required = false) String exchangeParam,
            @RequestParam(value = "network", required = false) String networkParam,
            HttpServletRequest request,
            Model model
    ) {
        StrategyType strategyType = parseStrategyType(typeRaw);

        String exchange = normalizeExchange(exchangeParam);
        NetworkType network = parseNetworkOrDefault(networkParam, NetworkType.TESTNET);

        // ✅ ОДНА строка на (chatId,type) — exchange/network храним в ней же (патчим)
        StrategySettings strategy = strategySettingsService.getOrCreate(chatId, strategyType);
        patchContext(strategy, exchange, network);
        strategySettingsService.save(strategy);

        // runtime status (active)
        try {
            StrategyRunInfo runtime = orchestrator.getStatus(chatId, strategyType, exchange, network);
            if (runtime != null) strategy.setActive(runtime.isActive());
        } catch (Exception e) {
            log.warn("⚠ Ошибка при получении статуса стратегии: {}", e.getMessage());
        }

        // BALANCE
        AccountBalanceSnapshot balance =
                accountBalanceService.getSnapshot(chatId, strategyType, exchange, network);

        // KEYS + DIAG
        ExchangeSettings exchangeSettings =
                exchangeSettingsService.getOrCreate(chatId, exchange, network);

        boolean diagnosticsSupported = isDiagnosticsSupported(exchange);

        ApiKeyDiagnostics diagnostics = null;
        if (diagnosticsSupported && exchangeSettings != null && exchangeSettings.hasBaseKeys()) {
            try {
                diagnostics = exchangeSettingsService.testConnectionDetailed(exchangeSettings);
            } catch (Exception e) {
                log.warn("⚠ diagnostics failed: {}", e.getMessage());
            }
        }
        boolean connectionOk = diagnostics != null && diagnostics.isOk();

        // selected asset: нормализуем и если пусто — берём из snapshot и фиксируем в БД
        String selectedAsset = normalizeAsset(strategy.getAccountAsset());
        if (selectedAsset == null && balance != null) {
            selectedAsset = normalizeAsset(balance.getSelectedAsset());
            if (selectedAsset != null) {
                strategy.setAccountAsset(selectedAsset);
                try { strategySettingsService.save(strategy); } catch (Exception ignored) {}
            }
        }

        // symbol info
        SymbolDescriptor symbolInfo = null;
        if (selectedAsset != null && strategy.getSymbol() != null && !strategy.getSymbol().isBlank()) {
            try {
                symbolInfo = marketSymbolService.getSymbolInfo(exchange, network, selectedAsset, strategy.getSymbol());
            } catch (Exception e) {
                log.warn("⚠ Не удалось получить symbolInfo symbol={}: {}", strategy.getSymbol(), e.getMessage());
            }
        }

        // fees
        AccountFees accountFees = null;
        if (diagnosticsSupported && exchangeSettings != null && exchangeSettings.hasBaseKeys() && connectionOk) {
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
            AdvancedControlMode mode = strategy.getAdvancedControlMode();
            if (mode == null) mode = AdvancedControlMode.MANUAL;

            strategyAdvancedHtml = advancedRenderer.render(
                    AdvancedRenderContext.builder()
                            .chatId(chatId)
                            .strategyType(strategyType)
                            .exchange(exchange)
                            .networkType(network)
                            .controlMode(mode)
                            .params(Map.of())
                            .build()
            );
        }

        model.addAttribute("page", "strategies/settings");

        model.addAttribute("chatId", chatId);
        model.addAttribute("type", strategyType);
        model.addAttribute("strategy", strategy);

        model.addAttribute("selectedExchange", exchange);
        model.addAttribute("selectedNetwork", network);

        model.addAttribute("availableExchanges", AVAILABLE_EXCHANGES);
        model.addAttribute("availableTimeframes", DEFAULT_TIMEFRAMES);

        model.addAttribute("exchangeSettings", exchangeSettings);
        model.addAttribute("diagnosticsSupported", diagnosticsSupported);
        model.addAttribute("diagnostics", diagnosticsSupported ? diagnostics : null);
        model.addAttribute("connectionOk", diagnosticsSupported && connectionOk);

        List<String> assets = (balance != null) ? balance.getAvailableAssets() : List.of();
        model.addAttribute("availableAssets", assets);
        model.addAttribute("selectedAsset", selectedAsset);

        AccountBalanceSnapshot.AssetBalance ab = (balance != null) ? balance.getSelectedBalance() : null;
        model.addAttribute("accountAssetBalance", ab);
        model.addAttribute("availableBalance", ab != null ? ab.getFree() : null);

        model.addAttribute("accountFees", accountFees);
        model.addAttribute("symbolInfo", symbolInfo);

        model.addAttribute("strategyAdvancedHtml", strategyAdvancedHtml);

        return "layout/app";
    }

    // =====================================================
    // POST — СОХРАНЕНИЕ (AJAX/FETCH) ✅ БЕЗ РЕДИРЕКТА
    // =====================================================
    @PostMapping(value = "/{type}/config", headers = "X-Requested-With=fetch")
    @ResponseBody
    @Transactional
    public ResponseEntity<?> saveSettingsFetch(
            @PathVariable("type") String typeRaw,
            @RequestParam("chatId") long chatId,
            @RequestParam("saveScope") String saveScope,
            @RequestParam Map<String, String> params
    ) {
        StrategyType strategyType = parseStrategyType(typeRaw);

        String exchange = normalizeExchange(params.get("exchange"));
        NetworkType network = parseNetworkOrDefault(params.get("network"), NetworkType.TESTNET);

        StrategySettings s = strategySettingsService.getOrCreate(chatId, strategyType);
        patchContext(s, exchange, network);

        // ✅ снимок ДО изменения
        CtxSnap before = snap(s);

        // CONTROL MODE + defaults
        AdvancedControlMode requestedMode = parseModeOrNull(params.get("advancedControlMode"));
        AdvancedControlMode currentMode = s.getAdvancedControlMode();
        if (requestedMode != null && requestedMode != currentMode) {
            s.setAdvancedControlMode(requestedMode);
            applyModeDefaults(s, requestedMode, network);
        }

        applySaveScope(chatId, strategyType, exchange, network, saveScope, params, s);

        // ✅ снимок ПОСЛЕ изменения (s уже сохранён в applySaveScope, если надо)
        CtxSnap after = snap(s);

        // кеш можно сразу инвалидировать
        settingsCache.invalidate(chatId, strategyType);

        // ✅ атомарный рестарт ТОЛЬКО если реально поменялся контекст и стратегия запущена
        scheduleRestartAfterCommitIfNeeded(chatId, strategyType, saveScope, before, after);

        return ResponseEntity.ok().build();
    }

    // =====================================================
    // POST — СОХРАНЕНИЕ (обычная форма) ✅ С РЕДИРЕКТОМ
    // =====================================================
    @PostMapping("/{type}/config")
    @Transactional
    public String saveSettings(
            @PathVariable("type") String typeRaw,
            @RequestParam("chatId") long chatId,
            @RequestParam("saveScope") String saveScope,
            @RequestParam Map<String, String> params
    ) {
        StrategyType strategyType = parseStrategyType(typeRaw);

        String exchange = normalizeExchange(params.get("exchange"));
        NetworkType network = parseNetworkOrDefault(params.get("network"), NetworkType.TESTNET);

        StrategySettings s = strategySettingsService.getOrCreate(chatId, strategyType);
        patchContext(s, exchange, network);

        // ✅ снимок ДО изменения
        CtxSnap before = snap(s);

        // CONTROL MODE + defaults
        AdvancedControlMode requestedMode = parseModeOrNull(params.get("advancedControlMode"));
        AdvancedControlMode currentMode = s.getAdvancedControlMode();
        if (requestedMode != null && requestedMode != currentMode) {
            s.setAdvancedControlMode(requestedMode);
            applyModeDefaults(s, requestedMode, network);
        }

        applySaveScope(chatId, strategyType, exchange, network, saveScope, params, s);

        // ✅ снимок ПОСЛЕ изменения
        CtxSnap after = snap(s);

        settingsCache.invalidate(chatId, strategyType);

        // ✅ рестарт после commit (чтобы start увидел новые настройки)
        scheduleRestartAfterCommitIfNeeded(chatId, strategyType, saveScope, before, after);

        String tab = params.getOrDefault("tab", "network");
        return buildRedirect(strategyType, chatId, exchange, network, tab);
    }

    // =========================================================
    // POST /apply — тюнинг (UI)
    // =========================================================
    @PostMapping("/apply")
    @ResponseBody
    @Transactional
    public ResponseEntity<ApplyResponse> apply(@RequestBody ApplyRequest req) {

        String ex = normalizeExchange(req.getExchange());
        NetworkType net = (req.getNetwork() != null) ? req.getNetwork() : NetworkType.TESTNET;

        StrategySettings s = strategySettingsService.getOrCreate(req.getChatId(), req.getType());
        patchContext(s, ex, net);

        AdvancedControlMode requested = parseModeOrNull(req.getAdvancedControlMode());
        AdvancedControlMode current = s.getAdvancedControlMode();
        if (requested != null && requested != current) {
            s.setAdvancedControlMode(requested);
            applyModeDefaults(s, requested, net);
            strategySettingsService.save(s);
        }

        AdvancedControlMode mode = s.getAdvancedControlMode();
        if (mode == null) mode = AdvancedControlMode.MANUAL;

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

            TuningResult result = autoTuner.tune(tr);

            boolean applied = result != null && result.applied();
            String reason = (result != null) ? result.reason() : "null";

            return ResponseEntity.ok(
                    ApplyResponse.builder()
                            .ok(true)
                            .mode(mode)
                            .applied(applied)
                            .reason(reason)
                            .build()
            );
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
    }

    @PostMapping("/settings/apply")
    @ResponseBody
    public ResponseEntity<ApplyResponse> applyAlias(@RequestBody ApplyRequest req) {
        return apply(req);
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
    // CORE SAVE LOGIC
    // =====================================================
    private void applySaveScope(
            long chatId,
            StrategyType strategyType,
            String exchange,
            NetworkType network,
            String saveScope,
            Map<String, String> params,
            StrategySettings s
    ) {
        if (s == null) return;

        // ✅ всегда фиксируем контекст в сущности
        patchContext(s, exchange, network);

        switch (saveScope) {

            case "network" -> {
                exchangeSettingsService.getOrCreate(chatId, exchange, network);
                strategySettingsService.save(s);
            }

            case "keys" -> exchangeSettingsService.saveKeys(
                    chatId,
                    exchange,
                    network,
                    params.get("apiKey"),
                    params.get("apiSecret"),
                    params.get("passphrase"),
                    params.get("subAccount")
            );

            case "trade" -> {
                String accountAsset = normalizeAsset(params.get("accountAsset"));
                if (accountAsset != null) s.setAccountAsset(accountAsset);

                String sym = normalizeSymbol(params.get("symbol"));
                if (sym != null) s.setSymbol(sym);

                String tf = normalizeTimeframe(params.get("timeframe"));
                if (tf != null) s.setTimeframe(tf);

                Integer candles = parseIntOrNull(params.get("cachedCandlesLimit"));
                if (candles != null) {
                    if (candles < 50) candles = 50;
                    s.setCachedCandlesLimit(candles);
                }

                strategySettingsService.save(s);
            }

            // =====================================================
            // ✅ RISK: ТОЛЬКО capitalMode + capitalValue
            // =====================================================
            case "risk" -> {

                StrategySettings.CapitalMode mode = parseCapitalModeOrDefault(
                        params.get("capitalMode"),
                        (s.getCapitalMode() != null ? s.getCapitalMode() : StrategySettings.CapitalMode.ALL)
                );

                BigDecimal value = parseBigDecimalOrNull(params.get("capitalValue"));

                if (mode == StrategySettings.CapitalMode.ALL) {
                    value = null;
                } else if (mode == StrategySettings.CapitalMode.FIX) {
                    value = validateMoneyOrNull(value);
                    if (value == null) mode = StrategySettings.CapitalMode.ALL;
                } else if (mode == StrategySettings.CapitalMode.PCT) {
                    value = validatePctOrNull(value);
                    if (value == null) mode = StrategySettings.CapitalMode.ALL;
                }

                s.setCapitalMode(mode);
                s.setCapitalValue(value);

                strategySettingsService.save(s);
            }

            case "general" -> strategySettingsService.save(s);

            case "advanced" -> {
                StrategyAdvancedRenderer renderer = strategyAdvancedRegistry.get(strategyType);

                AdvancedControlMode currentMode = s.getAdvancedControlMode();
                if (currentMode == null) currentMode = AdvancedControlMode.MANUAL;

                if (renderer != null && currentMode != AdvancedControlMode.AI) {
                    // ✅ чистим системные поля, чтобы renderer не видел мусор
                    HashMap<String, String> clean = new HashMap<>(params);
                    clean.remove("chatId");
                    clean.remove("saveScope");
                    clean.remove("tab");
                    clean.remove("exchange");
                    clean.remove("network");
                    clean.remove("type");

                    AdvancedRenderContext ctx =
                            AdvancedRenderContext.builder()
                                    .chatId(chatId)
                                    .strategyType(strategyType)
                                    .exchange(exchange)
                                    .networkType(network)
                                    .controlMode(currentMode)
                                    .params(clean)
                                    .build();
                    renderer.handleSubmit(ctx);
                }

                strategySettingsService.save(s);
            }

            default -> log.warn("⚠️ Unknown saveScope='{}'", saveScope);
        }
    }

    // =====================================================
    // MODE DEFAULTS (без collectEnabled)
    // =====================================================
    private void applyModeDefaults(StrategySettings s, AdvancedControlMode mode, NetworkType net) {
        if (s == null || mode == null) return;

        switch (mode) {
            case MANUAL -> {
                s.setAutoTuneEnabled(false);
                s.setMlGateEnabled(false);
                s.setRunPhase(PHASE_LIVE);
            }
            case HYBRID, AI -> {
                s.setAutoTuneEnabled(true);
                s.setMlGateEnabled(true);
                s.setRunPhase(net == NetworkType.TESTNET ? PHASE_PAPER : PHASE_LIVE);
            }
            default -> s.setRunPhase(PHASE_LIVE);
        }

        if (PHASE_BACKTEST.equalsIgnoreCase(s.getRunPhase())) {
            s.setRunPhase(PHASE_LIVE);
        }
        if (s.getRunPhase() == null || s.getRunPhase().isBlank()) {
            s.setRunPhase(PHASE_LIVE);
        }
    }

    // =====================================================
    // ✅ PATCH CONTEXT
    // =====================================================
    private void patchContext(StrategySettings s, String exchange, NetworkType network) {
        if (s == null) return;

        String ex = normalizeExchange(exchange);
        NetworkType net = (network != null) ? network : NetworkType.TESTNET;
        s.setExchangeName(ex);
        s.setNetworkType(net);
    }

    // =====================================================
    // ✅ AUTO-RESTART after commit
    // =====================================================
    private void scheduleRestartAfterCommitIfNeeded(long chatId,
                                                    StrategyType type,
                                                    String saveScope,
                                                    CtxSnap before,
                                                    CtxSnap after) {

        // рестартуем только если изменились symbol/tf/ex/net
        if (!ctxChanged(before, after)) return;

        // и только если стратегия запущена
        if (!orchestrator.isRunning(chatId, type)) return;

        // exchange/network берём из "после"
        final String ex = (after.exchange != null ? after.exchange : "BINANCE");
        final NetworkType net = (after.network != null ? after.network : NetworkType.TESTNET);
        final String reason = "ui_settings_changed:" + (saveScope == null ? "unknown" : saveScope);

        // чтобы start увидел новые settings — делаем рестарт после commit
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        orchestrator.restartStrategyAtomic(chatId, type, ex, net, reason);
                    } catch (Exception e) {
                        log.warn("⚠ restartStrategyAtomic failed chatId={} type={} ex={} net={} : {}",
                                chatId, type, ex, net, e.getMessage());
                    }
                }
            });
            return;
        }

        // fallback (на случай если кто-то вызовет без транзакции)
        try {
            orchestrator.restartStrategyAtomic(chatId, type, ex, net, reason);
        } catch (Exception e) {
            log.warn("⚠ restartStrategyAtomic failed chatId={} type={} ex={} net={} : {}",
                    chatId, type, ex, net, e.getMessage());
        }
    }

    private static final class CtxSnap {
        final String exchange;
        final NetworkType network;
        final String symbol;
        final String timeframe;

        private CtxSnap(String exchange, NetworkType network, String symbol, String timeframe) {
            this.exchange = exchange;
            this.network = network;
            this.symbol = symbol;
            this.timeframe = timeframe;
        }
    }

    private CtxSnap snap(StrategySettings s) {
        if (s == null) return new CtxSnap(null, null, null, null);
        return new CtxSnap(
                normalizeExchange(s.getExchangeName()),
                s.getNetworkType(),
                normalizeSymbol(s.getSymbol()),
                normalizeTimeframe(s.getTimeframe())
        );
    }

    private boolean ctxChanged(CtxSnap a, CtxSnap b) {
        if (a == null && b == null) return false;
        if (a == null || b == null) return true;
        if (!eq(a.exchange, b.exchange)) return true;
        if (a.network != b.network) return true;
        if (!eq(a.symbol, b.symbol)) return true;
        return !eq(a.timeframe, b.timeframe);
    }

    private boolean eq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }

    // =====================================================
    // POST — DIAGNOSE (UI)
    // =====================================================
    @PostMapping("/{type}/config/diagnose")
    @ResponseBody
    public ResponseEntity<DiagnoseResponse> diagnose(
            @PathVariable("type") String typeRaw,
            @RequestParam("chatId") long chatId,
            @RequestParam Map<String, String> params
    ) {
        StrategyType strategyType = parseStrategyType(typeRaw);

        String exchange = normalizeExchange(params.get("exchange"));
        NetworkType network = parseNetworkOrDefault(params.get("network"), NetworkType.TESTNET);

        DiagnoseResponse res = new DiagnoseResponse();
        res.ok = false;
        res.message = "Диагностика не выполнена";

        if (!isDiagnosticsSupported(exchange)) {
            res.message = "Диагностика недоступна для выбранной биржи: " + exchange;
            return ResponseEntity.ok(res);
        }

        ExchangeSettings es = exchangeSettingsService.getOrCreate(chatId, exchange, network);
        boolean hasKeys = (es != null && es.hasBaseKeys());

        if (!hasKeys) {
            res.message = "Ключи не заданы (apiKey/apiSecret пустые)";
            res.networkOk = true;
            return ResponseEntity.ok(res);
        }

        try {
            ApiKeyDiagnostics d = exchangeSettingsService.testConnectionDetailed(es);

            res.ok = (d != null && d.isOk());
            res.message = (d != null && d.getMessage() != null && !d.getMessage().isBlank())
                    ? d.getMessage()
                    : (res.ok ? "OK" : "Ошибка");

            if (d != null) {
                res.apiKeyValid       = d.isApiKeyValid();
                res.secretValid       = d.isSecretValid();
                res.signatureValid    = d.isSignatureValid();
                res.accountReadable   = d.isAccountReadable();
                res.tradingAllowed    = d.isTradingAllowed();
                res.ipAllowed         = d.isIpAllowed();
                res.networkOk         = d.isNetworkOk();
            }

            return ResponseEntity.ok(res);

        } catch (Exception e) {
            log.warn("⚠ diagnose failed chatId={} type={} ex={} net={}: {}",
                    chatId, strategyType, exchange, network, e.getMessage(), e);

            res.ok = false;
            res.message = "Ошибка диагностики: " + e.getMessage();
            return ResponseEntity.ok(res);
        }
    }

    @Data
    private static class DiagnoseResponse {
        private boolean ok;
        private String message;

        private Boolean apiKeyValid;
        private Boolean secretValid;
        private Boolean signatureValid;
        private Boolean accountReadable;
        private Boolean tradingAllowed;
        private Boolean ipAllowed;
        private Boolean networkOk;
    }

    // =====================================================
    // helpers
    // =====================================================

    private StrategySettings.CapitalMode parseCapitalModeOrDefault(String raw, StrategySettings.CapitalMode def) {
        if (def == null) def = StrategySettings.CapitalMode.ALL;
        if (raw == null || raw.isBlank()) return def;

        String v = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return StrategySettings.CapitalMode.valueOf(v);
        } catch (Exception ignored) {
            return def;
        }
    }

    private AdvancedControlMode parseModeOrNull(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (v.isEmpty()) return null;
        try { return AdvancedControlMode.valueOf(v); }
        catch (Exception ignored) { return null; }
    }

    private boolean isDiagnosticsSupported(String exchange) {
        String ex = normalizeExchange(exchange);
        return "BINANCE".equals(ex) || "BYBIT".equals(ex);
    }

    private String buildRedirect(StrategyType type, long chatId, String exchange, NetworkType network, String tab) {
        return "redirect:/strategies/" + type.name() +
               "/config?chatId=" + chatId +
               "&exchange=" + normalizeExchange(exchange) +
               "&network=" + network.name() +
               "&tab=" + (tab == null ? "network" : tab);
    }

    private StrategyType parseStrategyType(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Strategy type is blank");
        return StrategyType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }

    private String normalizeExchange(String exchange) {
        return (exchange == null || exchange.isBlank())
                ? "BINANCE"
                : exchange.trim().toUpperCase(Locale.ROOT);
    }

    private NetworkType parseNetworkOrDefault(String raw, NetworkType def) {
        if (def == null) def = NetworkType.TESTNET;
        if (raw == null || raw.isBlank()) return def;

        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (v.contains("TEST") || v.contains("DEMO")) return NetworkType.TESTNET;
        if (v.contains("MAIN")) return NetworkType.MAINNET;

        try { return NetworkType.valueOf(v); }
        catch (Exception e) { return def; }
    }

    private String normalizeAsset(String asset) {
        if (asset == null) return null;
        String a = asset.trim().toUpperCase(Locale.ROOT);
        return a.isEmpty() ? null : a;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private String normalizeTimeframe(String tf) {
        if (tf == null) return null;
        String t = tf.trim();
        return t.isEmpty() ? null : t;
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
        try {
            if (v == null) return null;
            String s = v.trim();
            if (s.isEmpty()) return null;
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal validatePctOrNull(BigDecimal v) {
        if (v == null) return null;
        if (v.compareTo(BigDecimal.ZERO) <= 0) return null;
        if (v.compareTo(BigDecimal.valueOf(100)) > 0) v = BigDecimal.valueOf(100);
        return v.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal validateMoneyOrNull(BigDecimal v) {
        if (v == null) return null;
        if (v.compareTo(BigDecimal.ZERO) <= 0) return null;
        return v.setScale(6, RoundingMode.HALF_UP);
    }
}
