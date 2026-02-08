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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

        StrategySettings strategy = strategySettingsService.getOrCreate(chatId, strategyType);

        // ✅ контекст для экрана — из query, иначе из БД
        String exchange = resolveExchange(exchangeParam, strategy);
        NetworkType network = resolveNetwork(networkParam, strategy);

        // ✅ сохраняем только если реально поменялось (а не “на каждый GET”)
        boolean ctxChanged = patchContextIfChanged(strategy, exchange, network);
        boolean phaseChanged = syncRunPhaseWithContextChanged(strategy);
        if (ctxChanged || phaseChanged) {
            try {
                strategySettingsService.save(strategy);
            } catch (Exception e) {
                log.warn("⚠ Не удалось сохранить контекст стратегии: {}", e.getMessage());
            }
        }

        // ✅ runtime status: строго в этом контексте (иначе UI путается)
        boolean runtimeActiveInCtx = orchestrator.isRunning(chatId, strategyType, exchange, network);
        boolean runtimeActiveAny   = orchestrator.isRunning(chatId, strategyType);

        model.addAttribute("runtimeActive", runtimeActiveInCtx);
        model.addAttribute("runtimeActiveAny", runtimeActiveAny);
        model.addAttribute("runtimeBinding", orchestrator.getBinding(chatId, strategyType).orElse(null));

        // selected asset из настроек
        String selectedAsset = normalizeAsset(strategy.getAccountAsset());

        AccountBalanceSnapshot balance = fetchSnapshotCompat(chatId, strategyType, exchange, network, selectedAsset);

        // если в БД пусто — берём из snapshot и фиксируем (ОК: разовая “починка” данных)
        if (selectedAsset == null && balance != null) {
            selectedAsset = normalizeAsset(balance.getSelectedAsset());
            if (selectedAsset != null) {
                strategy.setAccountAsset(selectedAsset);
                try { strategySettingsService.save(strategy); } catch (Exception ignored) {}
            }
        }

        ensureSelectedBalanceCompat(balance, selectedAsset);

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

        List<String> assets = (balance != null && balance.getAvailableAssets() != null) ? balance.getAvailableAssets() : List.of();
        model.addAttribute("availableAssets", assets);
        model.addAttribute("selectedAsset", selectedAsset);

        // баланс выбранного актива
        AccountBalanceSnapshot.AssetBalance ab = null;
        if (balance != null && selectedAsset != null) {
            ab = findBalance(balance, selectedAsset);
        }
        if (ab == null && balance != null) {
            ab = balance.getSelectedBalance();
        }

        model.addAttribute("accountAssetBalance", ab);
        model.addAttribute("availableBalance", ab != null ? ab.getFree() : null);

        model.addAttribute("accountFees", accountFees);
        model.addAttribute("symbolInfo", symbolInfo);
        model.addAttribute("strategyAdvancedHtml", strategyAdvancedHtml);

        return "layout/app";
    }

    // =====================================================
    // ✅ UI STATE (JSON)
    // =====================================================
    @GetMapping(value = "/{type}/config/state", produces = "application/json")
    @ResponseBody
    @Transactional // ⚠ не readOnly: getOrCreate может создавать запись
    public ResponseEntity<StrategyUiState> getUiState(
            @PathVariable("type") String typeRaw,
            @RequestParam("chatId") long chatId,
            @RequestParam(value = "exchange", required = false) String exchangeParam,
            @RequestParam(value = "network", required = false) String networkParam,
            @RequestParam(value = "diagnostics", required = false, defaultValue = "false") boolean diagnostics
    ) {
        StrategyType strategyType = parseStrategyType(typeRaw);

        StrategySettings s;
        try {
            s = strategySettingsService.getSettings(chatId, strategyType);
        } catch (Exception ignored) {
            s = null;
        }
        if (s == null) {
            s = strategySettingsService.getOrCreate(chatId, strategyType);
        }

        // ✅ контекст для ответа — из query, иначе из БД (без сохранения!)
        String ex = resolveExchange(exchangeParam, s);
        NetworkType net = resolveNetwork(networkParam, s);

        StrategyUiState state = buildUiState(chatId, strategyType, s, ex, net, diagnostics);
        return ResponseEntity.ok(state);
    }

    // =====================================================
    // POST — СОХРАНЕНИЕ (AJAX/FETCH)
    // =====================================================
    @PostMapping(value = "/{type}/config", headers = "X-Requested-With=fetch", produces = "application/json")
    @ResponseBody
    @Transactional
    public ResponseEntity<StrategyUiState> saveSettingsFetch(
            @PathVariable("type") String typeRaw,
            @RequestParam("chatId") long chatId,
            @RequestParam("saveScope") String saveScope,
            @RequestParam Map<String, String> params
    ) {
        StrategyType strategyType = parseStrategyType(typeRaw);

        StrategySettings s = strategySettingsService.getOrCreate(chatId, strategyType);

        CtxSnap before = snap(s);

        String exchange = resolveExchange(params.get("exchange"), s);
        NetworkType network = resolveNetwork(params.get("network"), s);

        patchContext(s, exchange, network);
        syncRunPhaseWithContext(s);

        AdvancedControlMode requestedMode = parseModeOrNull(params.get("advancedControlMode"));
        AdvancedControlMode currentMode = s.getAdvancedControlMode();

        if (requestedMode != null && requestedMode != currentMode) {
            s.setAdvancedControlMode(requestedMode);
            applyModeDefaults(s, requestedMode, s.getNetworkType());
            syncRunPhaseWithContext(s);
            strategySettingsService.save(s);
        }

        applySaveScope(chatId, strategyType, exchange, network, saveScope, params, s);

        CtxSnap after = snap(s);

        settingsCache.invalidate(chatId, strategyType);

        // ✅ 1) рестарт при смене контекста (ex/net/sym/tf)
        scheduleRestartAfterCommitIfNeeded(chatId, strategyType, saveScope, before, after);
        // ✅ 2) иначе — обновить runtime фазу/режим без рестарта
        scheduleRefreshRuntimeAfterCommitIfNeeded(chatId, strategyType, before, after, exchange, network);

        boolean includeDiagnostics = "network".equalsIgnoreCase(saveScope) || "keys".equalsIgnoreCase(saveScope);
        StrategyUiState state = buildUiState(chatId, strategyType, s, exchange, network, includeDiagnostics);

        return ResponseEntity.ok(state);
    }

    // =====================================================
    // POST — СОХРАНЕНИЕ (обычная форма)
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

        StrategySettings s = strategySettingsService.getOrCreate(chatId, strategyType);

        CtxSnap before = snap(s);

        String exchange = resolveExchange(params.get("exchange"), s);
        NetworkType network = resolveNetwork(params.get("network"), s);

        patchContext(s, exchange, network);
        syncRunPhaseWithContext(s);

        AdvancedControlMode requestedMode = parseModeOrNull(params.get("advancedControlMode"));
        AdvancedControlMode currentMode = s.getAdvancedControlMode();

        if (requestedMode != null && requestedMode != currentMode) {
            s.setAdvancedControlMode(requestedMode);
            applyModeDefaults(s, requestedMode, s.getNetworkType());
            syncRunPhaseWithContext(s);
            strategySettingsService.save(s);
        }

        applySaveScope(chatId, strategyType, exchange, network, saveScope, params, s);

        CtxSnap after = snap(s);

        settingsCache.invalidate(chatId, strategyType);

        // ✅ 1) рестарт при смене контекста (ex/net/sym/tf)
        scheduleRestartAfterCommitIfNeeded(chatId, strategyType, saveScope, before, after);
        // ✅ 2) иначе — обновить runtime фазу/режим без рестарта
        scheduleRefreshRuntimeAfterCommitIfNeeded(chatId, strategyType, before, after, exchange, network);

        String tab = params.getOrDefault("tab", "network");
        return buildRedirect(strategyType, chatId, exchange, network, tab);
    }

    // =========================================================
    // POST /apply — тюнинг (UI) ✅ AFTER COMMIT
    // =========================================================
    @PostMapping("/apply")
    @ResponseBody
    @Transactional
    public ResponseEntity<ApplyResponse> apply(@RequestBody ApplyRequest req) {

        StrategySettings s = strategySettingsService.getOrCreate(req.getChatId(), req.getType());

        String ex = resolveExchange(req.getExchange(), s);
        NetworkType net = (req.getNetwork() != null) ? req.getNetwork() : resolveNetwork(null, s);

        patchContext(s, ex, net);
        syncRunPhaseWithContext(s);

        AdvancedControlMode requested = parseModeOrNull(req.getAdvancedControlMode());
        AdvancedControlMode current = s.getAdvancedControlMode();
        if (requested != null && requested != current) {
            s.setAdvancedControlMode(requested);
            applyModeDefaults(s, requested, s.getNetworkType());
            syncRunPhaseWithContext(s);
            strategySettingsService.save(s);
        }

        AdvancedControlMode mode = s.getAdvancedControlMode();
        if (mode == null) mode = AdvancedControlMode.MANUAL;

        if (mode == AdvancedControlMode.MANUAL) {
            return ResponseEntity.ok(
                    ApplyResponse.builder()
                            .ok(true)
                            .accepted(false)
                            .mode(mode)
                            .applied(false)
                            .reason("MANUAL: apply не требуется")
                            .build()
            );
        }

        final long chatId = req.getChatId();
        final StrategyType type = req.getType();
        final String exchange = ex;
        final NetworkType network = net;

        final String reason = (req.getReason() == null || req.getReason().isBlank())
                ? "ui_apply"
                : req.getReason().trim();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runApplyTuneAndMaybeRestart(chatId, type, exchange, network, reason);
                }
            });
        } else {
            runApplyTuneAndMaybeRestart(chatId, type, exchange, network, reason);
        }

        return ResponseEntity.ok(
                ApplyResponse.builder()
                        .ok(true)
                        .accepted(true)
                        .mode(mode)
                        .applied(false)
                        .reason("Apply принят: тюнинг запущен после сохранения (afterCommit)")
                        .build()
        );
    }

    private void runApplyTuneAndMaybeRestart(long chatId,
                                             StrategyType type,
                                             String exchange,
                                             NetworkType network,
                                             String reason) {
        try {
            StrategySettings s = strategySettingsService.getOrCreate(chatId, type);

            patchContext(s, exchange, network);
            syncRunPhaseWithContext(s);
            try { strategySettingsService.save(s); } catch (Exception ignored) {}

            // ✅ обновим runtime фазу сразу (без рестарта) — важно для AI/COLLECT блокировки
            try { orchestrator.refreshRuntimePhase(chatId, type, exchange, network); } catch (Exception ignored) {}

            String symbol = normalizeSymbol(s.getSymbol());
            String timeframe = normalizeTimeframe(s.getTimeframe());
            Integer limit = s.getCachedCandlesLimit();

            if (symbol == null || timeframe == null || limit == null || limit <= 0) {
                log.warn("🧠 apply skipped: bad settings chatId={} type={} ex={} net={} sym={} tf={} limit={}",
                        chatId, type, exchange, network, symbol, timeframe, limit);
                return;
            }

            TuningRequest tr = TuningRequest.builder()
                    .chatId(chatId)
                    .strategyType(type)
                    .exchange(exchange)
                    .network(network)
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .candlesLimit(limit)
                    .reason(reason)
                    .build();

            TuningResult result = autoTuner.tune(tr);

            boolean applied = result != null && result.applied();
            String resReason = (result != null) ? result.reason() : "null";

            if (applied) {
                settingsCache.invalidate(chatId, type);

                // ✅ РЕСТАРТ ТОЛЬКО ЕСЛИ РАНТАЙМ ЗАПУЩЕН В ЭТОМ ЖЕ КОНТЕКСТЕ
                if (orchestrator.isRunning(chatId, type, exchange, network)) {
                    try {
                        orchestrator.restartStrategyAtomic(chatId, type, exchange, network, "ui_apply:tune_applied");
                    } catch (Exception e) {
                        log.warn("⚠ restart after apply failed chatId={} type={} ex={} net={}: {}",
                                chatId, type, exchange, network, e.getMessage());
                    }
                }

                log.info("🧠 apply tune DONE chatId={} type={} ex={} net={} applied=true reason={}",
                        chatId, type, exchange, network, safe(resReason));
            } else {
                log.info("🧠 apply tune DONE chatId={} type={} ex={} net={} applied=false reason={}",
                        chatId, type, exchange, network, safe(resReason));
            }

        } catch (Exception e) {
            log.error("🧠 apply tune FAILED chatId={} type={} ex={} net={}: {}",
                    chatId, type, exchange, network, e.getMessage(), e);
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
        private boolean accepted;
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

        patchContext(s, exchange, network);

        switch (saveScope) {

            case "network" -> {
                exchangeSettingsService.getOrCreate(chatId, exchange, network);
                syncRunPhaseWithContext(s);
                strategySettingsService.save(s);
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
                strategySettingsService.save(s);
            }

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
    // MODE DEFAULTS ✅ FIXED (AI -> COLLECT)
    // =====================================================
    private void applyModeDefaults(StrategySettings s, AdvancedControlMode mode, NetworkType net) {
        if (s == null || mode == null) return;

        switch (mode) {
            case MANUAL -> {
                s.setAutoTuneEnabled(false);
                s.setMlGateEnabled(false);
                s.setRunPhase(PHASE_LIVE);
            }
            case HYBRID -> {
                s.setAutoTuneEnabled(true);
                s.setMlGateEnabled(true);
                s.setRunPhase(net == NetworkType.TESTNET ? PHASE_PAPER : PHASE_LIVE);
            }
            case AI -> {
                s.setAutoTuneEnabled(true);
                s.setMlGateEnabled(true);
                s.setRunPhase(PHASE_COLLECT);
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
    // PATCH CONTEXT
    // =====================================================
    private void patchContext(StrategySettings s, String exchange, NetworkType network) {
        if (s == null) return;

        String ex = normalizeExchange(exchange);
        NetworkType net = (network != null)
                ? network
                : (s.getNetworkType() != null ? s.getNetworkType() : NetworkType.TESTNET);

        s.setExchangeName(ex);
        s.setNetworkType(net);
    }

    /**
     * ✅ PATCH, но только если реально есть изменения (чтобы GET не “писал” БД без причины)
     */
    private boolean patchContextIfChanged(StrategySettings s, String exchange, NetworkType network) {
        if (s == null) return false;

        String ex = (exchange == null || exchange.isBlank()) ? null : normalizeExchange(exchange);
        if (ex == null) ex = normalizeExchange(s.getExchangeName()); // fallback на текущее

        NetworkType net = (network != null) ? network : s.getNetworkType();

        boolean changed = false;

        String curEx = normalizeExchange(s.getExchangeName());
        if (ex != null && !eq(curEx, ex)) {
            s.setExchangeName(ex);
            changed = true;
        }

        if (net != null && s.getNetworkType() != net) {
            s.setNetworkType(net);
            changed = true;
        }

        return changed;
    }

    private boolean syncRunPhaseWithContextChanged(StrategySettings s) {
        if (s == null) return false;
        String before = (s.getRunPhase() == null) ? null : s.getRunPhase();
        syncRunPhaseWithContext(s);
        String after = (s.getRunPhase() == null) ? null : s.getRunPhase();
        if (before == null && after == null) return false;
        if (before == null || after == null) return true;
        return !before.equalsIgnoreCase(after);
    }

    /**
     * ✅ ВАЖНО:
     * - если runPhase = COLLECT/BACKTEST → не трогаем
     * - если mode = AI → держим COLLECT (AI цикл начинается оттуда)
     * - иначе: MANUAL -> LIVE, HYBRID -> PAPER/LIVE по net
     */
    private void syncRunPhaseWithContext(StrategySettings s) {
        if (s == null) return;

        String rp = (s.getRunPhase() == null) ? "" : s.getRunPhase().trim().toUpperCase(Locale.ROOT);

        if (PHASE_BACKTEST.equals(rp) || PHASE_COLLECT.equals(rp)) {
            return;
        }

        AdvancedControlMode mode = s.getAdvancedControlMode();
        if (mode == null) mode = AdvancedControlMode.MANUAL;

        if (mode == AdvancedControlMode.AI) {
            s.setRunPhase(PHASE_COLLECT);
            return;
        }

        NetworkType net = (s.getNetworkType() != null) ? s.getNetworkType() : NetworkType.TESTNET;

        String desired = (mode == AdvancedControlMode.MANUAL)
                ? PHASE_LIVE
                : (net == NetworkType.TESTNET ? PHASE_PAPER : PHASE_LIVE);

        if (s.getRunPhase() == null || !desired.equalsIgnoreCase(s.getRunPhase())) {
            s.setRunPhase(desired);
        }
    }

    // =====================================================
    // UI STATE builder
    // =====================================================
    private StrategyUiState buildUiState(long chatId, StrategyType type, StrategySettings s, String ex, NetworkType net, boolean diagnostics) {

        ex = normalizeExchange(ex);
        net = (net != null) ? net : (s.getNetworkType() != null ? s.getNetworkType() : NetworkType.TESTNET);

        // ✅ активность строго по контексту
        boolean active = orchestrator.isRunning(chatId, type, ex, net);

        String selectedAsset = normalizeAsset(s.getAccountAsset());

        AccountBalanceSnapshot snap = fetchSnapshotCompat(chatId, type, ex, net, selectedAsset);

        ExchangeSettings es = null;
        try {
            es = exchangeSettingsService.getOrCreate(chatId, ex, net);
        } catch (Exception ignored) {}

        Boolean hasKeys = (es != null) ? es.hasBaseKeys() : null;

        // ✅ НЕ сохраняем тут ничего в БД (state — read-only)
        if (selectedAsset == null && snap != null) {
            selectedAsset = normalizeAsset(snap.getSelectedAsset());
        }

        ensureSelectedBalanceCompat(snap, selectedAsset);

        StrategyUiState.AssetBalance balance = null;
        if (snap != null) {
            AccountBalanceSnapshot.AssetBalance ab = null;

            if (selectedAsset != null) ab = findBalance(snap, selectedAsset);
            if (ab == null) ab = snap.getSelectedBalance();

            if (ab != null) {
                String asset = normalizeAsset(selectedAsset != null ? selectedAsset : snap.getSelectedAsset());
                balance = new StrategyUiState.AssetBalance(
                        asset,
                        ab.getFree(),
                        ab.getLocked()
                );
            }
        }

        Boolean connectionOk = null;
        AccountFees fees = null;

        if (diagnostics && isDiagnosticsSupported(ex) && es != null && es.hasBaseKeys()) {
            try {
                ApiKeyDiagnostics d = exchangeSettingsService.testConnectionDetailed(es);
                connectionOk = (d != null && d.isOk());
                if (Boolean.TRUE.equals(connectionOk)) {
                    try { fees = accountBalanceService.getAccountFees(chatId, ex, net); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                connectionOk = false;
            }
        }

        List<String> assets = (snap != null && snap.getAvailableAssets() != null) ? snap.getAvailableAssets() : List.of();

        return new StrategyUiState(
                chatId,
                type,
                ex,
                net,
                active,
                s.getAdvancedControlMode(),
                s.getRunPhase(),
                s.isAutoTuneEnabled(),
                s.isMlGateEnabled(),
                normalizeAsset(selectedAsset),
                normalizeSymbol(s.getSymbol()),
                normalizeTimeframe(s.getTimeframe()),
                s.getCachedCandlesLimit(),
                s.getCapitalMode(),
                s.getCapitalValue(),
                assets,
                balance,
                hasKeys,
                connectionOk,
                fees
        );
    }

    public record StrategyUiState(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network,
            boolean active,
            AdvancedControlMode advancedControlMode,
            String runPhase,
            boolean autoTuneEnabled,
            boolean mlGateEnabled,
            String accountAsset,
            String symbol,
            String timeframe,
            Integer cachedCandlesLimit,
            StrategySettings.CapitalMode capitalMode,
            BigDecimal capitalValue,
            List<String> availableAssets,
            AssetBalance selectedBalance,
            Boolean hasKeys,
            Boolean connectionOk,
            AccountFees accountFees
    ) {
        public record AssetBalance(String asset, BigDecimal free, BigDecimal locked) {}
    }

    // =====================================================
    // AUTO-RESTART / REFRESH after commit
    // =====================================================
    private void scheduleRestartAfterCommitIfNeeded(long chatId,
                                                    StrategyType type,
                                                    String saveScope,
                                                    CtxSnap before,
                                                    CtxSnap after) {

        if (!ctxChanged(before, after)) return;
        if (!orchestrator.isRunning(chatId, type)) return;

        final String ex = (after.exchange != null ? after.exchange : "BINANCE");
        final NetworkType net = (after.network != null ? after.network : NetworkType.TESTNET);
        final String reason = "ui_settings_changed:" + (saveScope == null ? "unknown" : saveScope);

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

        try {
            orchestrator.restartStrategyAtomic(chatId, type, ex, net, reason);
        } catch (Exception e) {
            log.warn("⚠ restartStrategyAtomic failed chatId={} type={} ex={} net={} : {}",
                    chatId, type, ex, net, e.getMessage());
        }
    }

    /**
     * ✅ Если контекст НЕ менялся, но менялись режим/фаза — нужно обновить runtime cache оркестратора.
     * Это закрывает кейс: MANUAL/HYBRID/AI переключили, а рестарт не нужен.
     */
    private void scheduleRefreshRuntimeAfterCommitIfNeeded(long chatId,
                                                           StrategyType type,
                                                           CtxSnap before,
                                                           CtxSnap after,
                                                           String exchange,
                                                           NetworkType network) {

        // если будет рестарт — refresh не обязателен (рестарт сам обновит кэш), но и не вреден
        if (ctxChanged(before, after)) return;
        if (!orchestrator.isRunning(chatId, type)) return;

        final String ex = normalizeExchange(exchange);
        final NetworkType net = (network != null ? network : NetworkType.TESTNET);

        Runnable job = () -> {
            try {
                orchestrator.refreshRuntimePhase(chatId, type, ex, net);
            } catch (Exception e) {
                log.debug("⚠ refreshRuntimePhase failed chatId={} type={} ex={} net={} : {}",
                        chatId, type, ex, net, e.getMessage());
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    job.run();
                }
            });
        } else {
            job.run();
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

        StrategySettings s = strategySettingsService.getOrCreate(chatId, strategyType);
        String exchange = resolveExchange(params.get("exchange"), s);
        NetworkType network = resolveNetwork(params.get("network"), s);

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
    // COMPAT: snapshot selectedAsset
    // =====================================================
    private AccountBalanceSnapshot fetchSnapshotCompat(long chatId,
                                                       StrategyType type,
                                                       String exchange,
                                                       NetworkType network,
                                                       String selectedAsset) {
        AccountBalanceSnapshot snap = tryInvokeSnapshot(
                new Class<?>[]{long.class, StrategyType.class, String.class, NetworkType.class, String.class},
                new Object[]{chatId, type, exchange, network, selectedAsset}
        );
        if (snap != null) return snap;

        return tryInvokeSnapshot(
                new Class<?>[]{long.class, StrategyType.class, String.class, NetworkType.class},
                new Object[]{chatId, type, exchange, network}
        );
    }

    private AccountBalanceSnapshot tryInvokeSnapshot(Class<?>[] sig, Object[] args) {
        try {
            Method m = accountBalanceService.getClass().getMethod("getSnapshot", sig);
            Object r = m.invoke(accountBalanceService, args);
            if (r instanceof AccountBalanceSnapshot s) return s;
            return null;
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Exception e) {
            log.warn("⚠ getSnapshot invoke failed: {}", e.getMessage());
            return null;
        }
    }

    private void ensureSelectedBalanceCompat(AccountBalanceSnapshot snap, String selectedAsset) {
        if (snap == null || selectedAsset == null || selectedAsset.isBlank()) return;

        try {
            Method m = snap.getClass().getMethod("selectAsset", String.class);
            m.invoke(snap, selectedAsset);
        } catch (Exception ignored) {
            // ok
        }
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

    private String resolveExchange(String raw, StrategySettings s) {
        if (raw != null && !raw.isBlank()) return normalizeExchange(raw);
        if (s != null && s.getExchangeName() != null && !s.getExchangeName().isBlank())
            return normalizeExchange(s.getExchangeName());
        return "BINANCE";
    }

    private NetworkType resolveNetwork(String raw, StrategySettings s) {
        if (raw != null && !raw.isBlank()) return parseNetworkOrDefault(raw, NetworkType.TESTNET);
        if (s != null && s.getNetworkType() != null) return s.getNetworkType();
        return NetworkType.TESTNET;
    }

    private static String safe(String s) {
        if (s == null) return "";
        String x = s.trim();
        return x.length() > 200 ? x.substring(0, 200) : x;
    }

    @SuppressWarnings("unchecked")
    private AccountBalanceSnapshot.AssetBalance findBalance(AccountBalanceSnapshot snap, String asset) {
        if (snap == null || asset == null || asset.isBlank()) return null;

        String a = asset.trim().toUpperCase(Locale.ROOT);

        // 1) пробуем getBalancesByAsset()
        try {
            Method m = snap.getClass().getMethod("getBalancesByAsset");
            Object r = m.invoke(snap);

            if (r instanceof Map<?, ?> map) {
                Object v = map.get(a);
                if (v instanceof AccountBalanceSnapshot.AssetBalance ab) return ab;
            }
        } catch (Exception ignored) {}

        // 2) поле balancesByAsset
        try {
            Field f = snap.getClass().getDeclaredField("balancesByAsset");
            f.setAccessible(true);
            Object r = f.get(snap);

            if (r instanceof Map<?, ?> map) {
                Object v = map.get(a);
                if (v instanceof AccountBalanceSnapshot.AssetBalance ab) return ab;
            }
        } catch (Exception ignored) {}

        // 3) selectedBalance
        try {
            return snap.getSelectedBalance();
        } catch (Exception ignored) {
            return null;
        }
    }
}
