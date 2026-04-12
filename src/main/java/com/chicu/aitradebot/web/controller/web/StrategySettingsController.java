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
import com.chicu.aitradebot.market.dto.MarketOverviewDto;
import com.chicu.aitradebot.market.model.SymbolDescriptor;
import com.chicu.aitradebot.market.service.MarketInfoService;
import com.chicu.aitradebot.market.service.MarketSymbolService;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.service.StrategySettingsCommandService;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
@RequestMapping("/strategies")
public class StrategySettingsController {

    private final StrategySettingsCommandService strategySettingsCommandService;
    private final StrategySettingsService strategySettingsService;
    private final ExchangeSettingsService exchangeSettingsService;
    private final StrategySettingsCache settingsCache;
    private final AccountBalanceService accountBalanceService;
    private final MarketInfoService marketInfoService;
    private final MarketSymbolService marketSymbolService;
    private final StrategyAdvancedRegistry strategyAdvancedRegistry;
    private final AiStrategyOrchestrator orchestrator;
    private final AutoTunerOrchestrator autoTuner;

    private static final List<String> DEFAULT_TIMEFRAMES = List.of(
            "1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "12h", "1d", "1w", "1mo"
    );

    private static final List<String> AVAILABLE_EXCHANGES = List.of("BINANCE", "BYBIT", "OKX");

    private static final String PHASE_LIVE = "LIVE";
    private static final String PHASE_PAPER = "PAPER";
    private static final String PHASE_BACKTEST = "BACKTEST";
    private static final String PHASE_COLLECT = "COLLECT";

    @GetMapping("/{type}/config")
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

        String exchange = resolveExchange(exchangeParam, strategy);
        NetworkType network = resolveNetwork(networkParam, strategy);

        boolean ctxChanged = patchContextIfChanged(strategy, exchange, network);
        boolean phaseChanged = syncRunPhaseWithContextChanged(strategy, exchange, network);
        List<String> availableTimeframes = resolveAvailableTimeframes(exchange, network);
        boolean timeframeChanged = ensureAllowedTimeframe(strategy, availableTimeframes);
        if (ctxChanged || phaseChanged || timeframeChanged) {
            try {
                strategy = saveStrategySettings(chatId, strategyType, strategy);
            } catch (Exception e) {
                log.warn("⚠ Не удалось сохранить контекст стратегии: {}", e.getMessage());
            }
        }

        boolean runtimeActiveInCtx = orchestrator.isRunning(chatId, strategyType, exchange, network);
        boolean runtimeActiveAny = orchestrator.isRunning(chatId, strategyType);

        model.addAttribute("runtimeActive", runtimeActiveInCtx);
        model.addAttribute("runtimeActiveAny", runtimeActiveAny);
        model.addAttribute("runtimeBinding", orchestrator.getBinding(chatId, strategyType).orElse(null));

        String selectedAssetBefore = normalizeAsset(strategy.getAccountAsset());
        AccountBalanceSnapshot balance = fetchSnapshotCompat(chatId, strategyType, exchange, network, selectedAssetBefore);

        String selectedAsset = resolveSelectedAsset(selectedAssetBefore, balance);
        if (!eq(selectedAssetBefore, selectedAsset) && selectedAsset != null) {
            strategy.setAccountAsset(selectedAsset);
            try {
                strategy = saveStrategySettings(chatId, strategyType, strategy);
            } catch (Exception ignored) {
            }
        }

        AccountBalanceSnapshot.AssetBalance ab = resolveAssetBalance(balance, selectedAsset);
        List<String> assets = resolveAvailableAssets(balance, selectedAsset);

        ExchangeSettings exchangeSettings = exchangeSettingsService.getOrCreate(chatId, exchange, network);

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

        SymbolDescriptor symbolInfo = null;
        if (selectedAsset != null && strategy.getSymbol() != null && !strategy.getSymbol().isBlank()) {
            try {
                symbolInfo = marketSymbolService.getSymbolInfo(exchange, network, selectedAsset, strategy.getSymbol());
            } catch (Exception e) {
                log.warn("⚠ Не удалось получить symbolInfo symbol={}: {}", strategy.getSymbol(), e.getMessage());
            }
        }

        AccountFees accountFees = null;
        if (diagnosticsSupported && exchangeSettings != null && exchangeSettings.hasBaseKeys() && connectionOk) {
            try {
                accountFees = accountBalanceService.getAccountFees(chatId, exchange, network);
            } catch (Exception e) {
                log.warn("⚠ Не удалось получить комиссии: {}", e.getMessage());
            }
        }

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
        model.addAttribute("availableTimeframes", availableTimeframes);

        model.addAttribute("exchangeSettings", exchangeSettings);
        model.addAttribute("diagnosticsSupported", diagnosticsSupported);
        model.addAttribute("diagnostics", diagnosticsSupported ? diagnostics : null);
        model.addAttribute("connectionOk", diagnosticsSupported && connectionOk);

        model.addAttribute("availableAssets", assets);
        model.addAttribute("selectedAsset", selectedAsset);

        model.addAttribute("accountAssetBalance", ab);
        model.addAttribute("availableBalance", ab != null ? ab.getFree() : null);

        model.addAttribute("accountBalanceConnectionOk", balance != null ? balance.isConnectionOk() : null);
        model.addAttribute("accountBalanceError", balance != null ? balance.getError() : null);

        model.addAttribute("accountFees", accountFees);
        model.addAttribute("symbolInfo", symbolInfo);
        model.addAttribute("strategyAdvancedHtml", strategyAdvancedHtml);

        return "layout/app";
    }

    @GetMapping(value = "/{type}/config/state", produces = "application/json")
    @ResponseBody
    public ResponseEntity<StrategyUiState> getUiState(
            @PathVariable("type") String typeRaw,
            @RequestParam("chatId") long chatId,
            @RequestParam(value = "exchange", required = false) String exchangeParam,
            @RequestParam(value = "network", required = false) String networkParam,
            @RequestParam(value = "diagnostics", required = false, defaultValue = "false") boolean diagnostics,
            @RequestParam(value = "lite", required = false, defaultValue = "false") boolean lite
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

        String ex = resolveExchange(exchangeParam, s);
        NetworkType net = resolveNetwork(networkParam, s);

        StrategyUiState state = buildUiState(chatId, strategyType, s, ex, net, diagnostics, !lite, false, null, null);
        return ResponseEntity.ok(state);
    }

    @PostMapping(value = "/{type}/config", headers = "X-Requested-With=fetch", produces = "application/json")
    @ResponseBody
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
            applyModeDefaultsHard(s, requestedMode, s.getNetworkType());
            syncRunPhaseWithContext(s);
            s = saveStrategySettings(chatId, strategyType, s);
        }

        s = applySaveScope(chatId, strategyType, exchange, network, saveScope, params, s);

        List<String> availableTimeframes = resolveAvailableTimeframes(exchange, network);
        if (ensureAllowedTimeframe(s, availableTimeframes)) {
            s = saveStrategySettings(chatId, strategyType, s);
        }

        CtxSnap after = snap(s);
        boolean contextChanged = ctxChanged(before, after);

        settingsCache.invalidate(chatId, strategyType);

        scheduleRestartAfterCommitIfNeeded(chatId, strategyType, saveScope, before, after);
        scheduleRefreshRuntimeAfterCommitIfNeeded(chatId, strategyType, before, after, exchange, network);

        boolean includeDiagnostics = "network".equalsIgnoreCase(saveScope) || "keys".equalsIgnoreCase(saveScope);
        StrategyUiState state = buildUiState(
                chatId,
                strategyType,
                s,
                exchange,
                network,
                includeDiagnostics,
                true,
                contextChanged,
                buildConfigPageUrl(strategyType, chatId, s, params.getOrDefault("tab", "network")),
                buildDashboardPageUrl(strategyType, chatId, s)
        );

        return ResponseEntity.ok(state);
    }

    @PostMapping("/{type}/config")
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
            applyModeDefaultsHard(s, requestedMode, s.getNetworkType());
            syncRunPhaseWithContext(s);
            s = saveStrategySettings(chatId, strategyType, s);
        }

        s = applySaveScope(chatId, strategyType, exchange, network, saveScope, params, s);

        List<String> availableTimeframes = resolveAvailableTimeframes(exchange, network);
        if (ensureAllowedTimeframe(s, availableTimeframes)) {
            s = saveStrategySettings(chatId, strategyType, s);
        }

        CtxSnap after = snap(s);

        settingsCache.invalidate(chatId, strategyType);

        scheduleRestartAfterCommitIfNeeded(chatId, strategyType, saveScope, before, after);
        scheduleRefreshRuntimeAfterCommitIfNeeded(chatId, strategyType, before, after, exchange, network);

        String tab = params.getOrDefault("tab", "network");
        return buildRedirect(strategyType, chatId, exchange, network, tab);
    }

    @PostMapping("/apply")
    @ResponseBody
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
            applyModeDefaultsHard(s, requested, s.getNetworkType());
            syncRunPhaseWithContext(s);
            s = saveStrategySettings(req.getChatId(), req.getType(), s);
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
                        .reason("Apply принят: тюнинг запущен")
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
            s = saveStrategySettings(chatId, type, s);

            try {
                orchestrator.refreshRuntimePhase(chatId, type, exchange, network);
            } catch (Exception ignored) {
            }

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

    private StrategySettings applySaveScope(
            long chatId,
            StrategyType strategyType,
            String exchange,
            NetworkType network,
            String saveScope,
            Map<String, String> params,
            StrategySettings s
    ) {
        if (s == null) return null;

        patchContext(s, exchange, network);

        switch (saveScope) {

            case "network" -> {
                exchangeSettingsService.getOrCreate(chatId, exchange, network);
                syncRunPhaseWithContext(s);
                return saveStrategySettings(chatId, strategyType, s);
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
                return saveStrategySettings(chatId, strategyType, s);
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

                return saveStrategySettings(chatId, strategyType, s);
            }

            case "risk" -> {
                String currentAccountAsset = normalizeAsset(s.getAccountAsset());
                String accountAsset = normalizeAsset(params.get("accountAsset"));
                if (accountAsset != null) {
                    s.setAccountAsset(accountAsset);
                } else {
                    accountAsset = currentAccountAsset;
                }

                StrategySettings.CapitalMode currentMode =
                        (s.getCapitalMode() != null ? s.getCapitalMode() : StrategySettings.CapitalMode.ALL);
                BigDecimal currentValue = s.getCapitalValue();

                boolean hasModeParam = params.containsKey("capitalMode");
                boolean hasValueParam = params.containsKey("capitalValue");

                StrategySettings.CapitalMode mode = currentMode;
                if (hasModeParam) {
                    mode = parseCapitalModeOrDefault(params.get("capitalMode"), currentMode);
                }

                BigDecimal value = currentValue;

                if (mode == StrategySettings.CapitalMode.ALL) {
                    value = null;
                } else if (hasValueParam) {
                    BigDecimal parsed = parseBigDecimalOrNull(params.get("capitalValue"));
                    if (mode == StrategySettings.CapitalMode.FIX) {
                        value = validateMoneyOrNull(parsed);
                    } else if (mode == StrategySettings.CapitalMode.PCT) {
                        value = validatePctOrNull(parsed);
                    }
                }

                s.setCapitalMode(mode);
                s.setCapitalValue(value);

                log.info("🛡️ [RISK SAVE] chatId={} type={} accountAsset={} rawMode={} rawValue={} prevMode={} prevValue={} nextMode={} nextValue={}",
                        chatId, strategyType,
                        accountAsset,
                        params.get("capitalMode"),
                        params.get("capitalValue"),
                        currentMode, currentValue,
                        mode, value);

                return saveStrategySettings(chatId, strategyType, s);
            }

            case "general" -> {
                return saveStrategySettings(chatId, strategyType, s);
            }

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

                return saveStrategySettings(chatId, strategyType, s);
            }

            default -> {
                log.warn("⚠️ Unknown saveScope='{}'", saveScope);
                return s;
            }
        }
    }

    private void applyModeDefaultsHard(StrategySettings s) {
        if (s == null) return;
        AdvancedControlMode m = (s.getAdvancedControlMode() != null) ? s.getAdvancedControlMode() : AdvancedControlMode.MANUAL;
        applyModeDefaultsHard(s, m, s.getNetworkType());
    }

    private void applyModeDefaultsHard(StrategySettings s, AdvancedControlMode mode, NetworkType net) {
        if (s == null || mode == null) return;

        NetworkType n = (net != null) ? net : (s.getNetworkType() != null ? s.getNetworkType() : NetworkType.TESTNET);

        switch (mode) {
            case MANUAL -> {
                s.setAutoTuneEnabled(false);
                s.setMlGateEnabled(false);
                s.setRunPhase(PHASE_LIVE);

                s.setGateMinProb(null);
                s.setMlModelKey(null);
                s.setMlSchemaHash(null);
                s.setMlModelVersion(null);
            }
            case HYBRID -> {
                s.setAutoTuneEnabled(false);
                s.setMlGateEnabled(true);

                if (s.getGateMinProb() == null) {
                    s.setGateMinProb(new BigDecimal("0.550000"));
                }

                s.setRunPhase(n == NetworkType.TESTNET ? PHASE_PAPER : PHASE_LIVE);
            }
            case AI -> {
                s.setAutoTuneEnabled(true);
                if (!s.isMlGateEnabled()) {
                    s.setMlGateEnabled(true);
                }
                if (s.getGateMinProb() == null && s.isMlGateEnabled()) {
                    s.setGateMinProb(new BigDecimal("0.550000"));
                }

                s.setRunPhase(n == NetworkType.TESTNET ? PHASE_PAPER : PHASE_LIVE);
            }
        }

        if (PHASE_BACKTEST.equalsIgnoreCase(s.getRunPhase())) {
            s.setRunPhase(PHASE_LIVE);
        }
        if (s.getRunPhase() == null || s.getRunPhase().isBlank()) {
            s.setRunPhase(PHASE_LIVE);
        }
    }

    private void patchContext(StrategySettings s, String exchange, NetworkType network) {
        if (s == null) return;

        String ex = normalizeExchange(exchange);
        NetworkType net = (network != null)
                ? network
                : (s.getNetworkType() != null ? s.getNetworkType() : NetworkType.TESTNET);

        s.setExchangeName(ex);
        s.setNetworkType(net);
    }

    private boolean patchContextIfChanged(StrategySettings s, String exchange, NetworkType network) {
        if (s == null) return false;

        String ex = (exchange == null || exchange.isBlank()) ? null : normalizeExchange(exchange);
        if (ex == null) ex = normalizeExchange(s.getExchangeName());

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
        return syncRunPhaseWithContextChanged(s, s.getExchangeName(), s.getNetworkType());
    }

    private boolean syncRunPhaseWithContextChanged(StrategySettings s, String exchange, NetworkType network) {
        if (s == null) return false;

        NetworkType net = (network != null) ? network : s.getNetworkType();

        String before = normalizeUpperNullable(s.getRunPhase());

        if (PHASE_BACKTEST.equals(before) || PHASE_COLLECT.equals(before)) {
            return false;
        }

        syncRunPhaseWithContext(s, exchange, net);

        String after = normalizeUpperNullable(s.getRunPhase());
        return !Objects.equals(before, after);
    }

    private void syncRunPhaseWithContext(StrategySettings s) {
        if (s == null) return;
        syncRunPhaseWithContext(s, s.getExchangeName(), s.getNetworkType());
    }

    private void syncRunPhaseWithContext(StrategySettings s, String exchange, NetworkType network) {
        if (s == null) return;

        AdvancedControlMode mode = (s.getAdvancedControlMode() != null) ? s.getAdvancedControlMode() : AdvancedControlMode.MANUAL;
        NetworkType net = (network != null) ? network : s.getNetworkType();

        boolean isTestnet = (net == null || net == NetworkType.TESTNET);

        String desired;
        if (mode == AdvancedControlMode.MANUAL) {
            desired = PHASE_LIVE;
        } else {
            desired = isTestnet ? PHASE_PAPER : PHASE_LIVE;
        }

        if (!isTestnet && PHASE_PAPER.equalsIgnoreCase(s.getRunPhase())) {
            s.setRunPhase(PHASE_LIVE);
            return;
        }

        if (s.getRunPhase() == null || s.getRunPhase().isBlank()) {
            s.setRunPhase(desired);
            return;
        }

        String cur = normalizeUpperNullable(s.getRunPhase());

        if (PHASE_BACKTEST.equals(cur) || PHASE_COLLECT.equals(cur)) {
            if (mode == AdvancedControlMode.MANUAL) return;
            if (!desired.equals(cur)) {
                s.setRunPhase(desired);
            }
            return;
        }

        if (!desired.equals(cur)) {
            s.setRunPhase(desired);
        }
    }

    private StrategyUiState buildUiState(long chatId,
                                         StrategyType type,
                                         StrategySettings s,
                                         String ex,
                                         NetworkType net,
                                         boolean diagnostics,
                                         boolean includeBalance,
                                         boolean contextChanged,
                                         String redirectConfigUrl,
                                         String redirectDashboardUrl) {

        ex = normalizeExchange(ex);
        net = (net != null) ? net : (s.getNetworkType() != null ? s.getNetworkType() : NetworkType.TESTNET);

        boolean active = orchestrator.isRunning(chatId, type, ex, net);

        String selectedAsset = normalizeAsset(s.getAccountAsset());
        AccountBalanceSnapshot snap = includeBalance ? fetchSnapshotCompat(chatId, type, ex, net, selectedAsset) : null;

        selectedAsset = includeBalance ? resolveSelectedAsset(selectedAsset, snap) : normalizeAsset(selectedAsset);
        AccountBalanceSnapshot.AssetBalance ab = includeBalance ? resolveAssetBalance(snap, selectedAsset) : null;
        List<String> assets = includeBalance
                ? resolveAvailableAssets(snap, selectedAsset)
                : (selectedAsset != null ? new ArrayList<>(List.of(selectedAsset)) : new ArrayList<>());

        ExchangeSettings es = null;
        try {
            es = exchangeSettingsService.getOrCreate(chatId, ex, net);
        } catch (Exception ignored) {
        }

        Boolean hasKeys = (es != null) ? es.hasBaseKeys() : null;

        List<String> availableTimeframes = resolveAvailableTimeframes(ex, net);
        String effectiveTimeframe = chooseAllowedTimeframe(normalizeTimeframe(s.getTimeframe()), availableTimeframes);

        StrategyUiState.AssetBalance balance = null;
        if (ab != null) {
            balance = new StrategyUiState.AssetBalance(selectedAsset, ab.getFree(), ab.getLocked());
        }

        Boolean connectionOk = (snap != null) ? snap.isConnectionOk() : null;
        AccountFees fees = null;

        if (includeBalance && diagnostics && isDiagnosticsSupported(ex) && es != null && es.hasBaseKeys()) {
            try {
                ApiKeyDiagnostics d = exchangeSettingsService.testConnectionDetailed(es);
                connectionOk = (d != null && d.isOk());
                if (Boolean.TRUE.equals(connectionOk)) {
                    try {
                        fees = accountBalanceService.getAccountFees(chatId, ex, net);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                connectionOk = false;
            }
        }

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
                effectiveTimeframe,
                s.getCachedCandlesLimit(),
                s.getCapitalMode(),
                s.getCapitalValue(),
                availableTimeframes,
                assets,
                balance,
                hasKeys,
                connectionOk,
                fees,
                contextChanged,
                redirectConfigUrl,
                redirectDashboardUrl
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
            List<String> availableTimeframes,
            List<String> availableAssets,
            AssetBalance selectedBalance,
            Boolean hasKeys,
            Boolean connectionOk,
            AccountFees accountFees,
            boolean contextChanged,
            String redirectConfigUrl,
            String redirectDashboardUrl
    ) {
        public record AssetBalance(String asset, BigDecimal free, BigDecimal locked) {
        }
    }

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

    private void scheduleRefreshRuntimeAfterCommitIfNeeded(long chatId,
                                                           StrategyType type,
                                                           CtxSnap before,
                                                           CtxSnap after,
                                                           String exchange,
                                                           NetworkType network) {

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
                res.apiKeyValid = d.isApiKeyValid();
                res.secretValid = d.isSecretValid();
                res.signatureValid = d.isSignatureValid();
                res.accountReadable = d.isAccountReadable();
                res.tradingAllowed = d.isTradingAllowed();
                res.ipAllowed = d.isIpAllowed();
                res.networkOk = d.isNetworkOk();
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
        }
    }

    private String resolveSelectedAsset(String preferred, AccountBalanceSnapshot snap) {
        String selected = normalizeAsset(preferred);
        if (selected != null) return selected;
        if (snap == null) return null;

        selected = normalizeAsset(snap.getSelectedAsset());
        if (selected != null) return selected;

        List<String> assets = snap.getAvailableAssets();
        if (assets != null) {
            for (String asset : assets) {
                String a = normalizeAsset(asset);
                if (a != null) return a;
            }
        }

        Map<String, AccountBalanceSnapshot.AssetBalance> balances = getSnapshotBalances(snap);
        if (!balances.isEmpty()) {
            for (String asset : balances.keySet()) {
                String a = normalizeAsset(asset);
                if (a != null) return a;
            }
        }

        return null;
    }

    private List<String> resolveAvailableAssets(AccountBalanceSnapshot snap, String selectedAsset) {
        LinkedHashSet<String> out = new LinkedHashSet<>();

        if (snap != null && snap.getAvailableAssets() != null) {
            for (String a : snap.getAvailableAssets()) {
                String n = normalizeAsset(a);
                if (n != null) out.add(n);
            }
        }

        if (snap != null) {
            out.addAll(getSnapshotBalances(snap).keySet().stream()
                    .map(this::normalizeAsset)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
        }

        String selected = normalizeAsset(selectedAsset);
        if (selected != null) {
            out.add(selected);
        }

        return new ArrayList<>(out);
    }

    private AccountBalanceSnapshot.AssetBalance resolveAssetBalance(AccountBalanceSnapshot snap, String selectedAsset) {
        if (snap == null) return null;

        String asset = normalizeAsset(selectedAsset);
        ensureSelectedBalanceCompat(snap, asset);

        if (asset != null) {
            AccountBalanceSnapshot.AssetBalance ab = findBalance(snap, asset);
            if (ab != null) return ab;
        }

        if (snap.getSelectedBalance() != null) {
            return snap.getSelectedBalance();
        }

        Map<String, AccountBalanceSnapshot.AssetBalance> balances = getSnapshotBalances(snap);
        if (!balances.isEmpty()) {
            return balances.values().iterator().next();
        }

        return null;
    }

    private Map<String, AccountBalanceSnapshot.AssetBalance> getSnapshotBalances(AccountBalanceSnapshot snap) {
        if (snap == null) return Collections.emptyMap();

        try {
            Method m = snap.getClass().getMethod("getBalances");
            Object r = m.invoke(snap);
            if (r instanceof Map<?, ?> map) {
                Map<String, AccountBalanceSnapshot.AssetBalance> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() == null || !(e.getValue() instanceof AccountBalanceSnapshot.AssetBalance ab)) continue;
                    String asset = normalizeAsset(String.valueOf(e.getKey()));
                    if (asset != null) out.put(asset, ab);
                }
                return out;
            }
        } catch (Exception ignored) {
        }

        try {
            Field f = snap.getClass().getDeclaredField("balances");
            f.setAccessible(true);
            Object r = f.get(snap);
            if (r instanceof Map<?, ?> map) {
                Map<String, AccountBalanceSnapshot.AssetBalance> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() == null || !(e.getValue() instanceof AccountBalanceSnapshot.AssetBalance ab)) continue;
                    String asset = normalizeAsset(String.valueOf(e.getKey()));
                    if (asset != null) out.put(asset, ab);
                }
                return out;
            }
        } catch (Exception ignored) {
        }

        return Collections.emptyMap();
    }

    private StrategySettings saveStrategySettings(long chatId, StrategyType type, StrategySettings patch) {
        return strategySettingsCommandService.savePatchWithRetry(chatId, type, patch);
    }

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
        try {
            return AdvancedControlMode.valueOf(v);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isDiagnosticsSupported(String exchange) {
        String ex = normalizeExchange(exchange);
        return "BINANCE".equals(ex) || "BYBIT".equals(ex);
    }


    private String buildConfigPageUrl(StrategyType type, long chatId, StrategySettings s, String tab) {
        String ex = normalizeExchange(s != null ? s.getExchangeName() : null);
        NetworkType net = (s != null && s.getNetworkType() != null) ? s.getNetworkType() : NetworkType.TESTNET;
        String safeTab = (tab == null || tab.isBlank()) ? "network" : tab.trim();
        return "/strategies/" + type.name()
               + "/config?chatId=" + chatId
               + "&exchange=" + enc(ex)
               + "&network=" + enc(net.name())
               + "&tab=" + enc(safeTab);
    }

    private String buildDashboardPageUrl(StrategyType type, long chatId, StrategySettings s) {
        String ex = normalizeExchange(s != null ? s.getExchangeName() : null);
        NetworkType net = (s != null && s.getNetworkType() != null) ? s.getNetworkType() : NetworkType.TESTNET;
        String symbol = normalizeSymbol(s != null ? s.getSymbol() : null);
        String timeframe = normalizeTimeframe(s != null ? s.getTimeframe() : null);

        StringBuilder url = new StringBuilder();
        url.append("/strategies/")
                .append(type.name())
                .append("/dashboard")
                .append("?chatId=").append(chatId)
                .append("&exchange=").append(enc(ex))
                .append("&network=").append(enc(net.name()));

        if (symbol != null) {
            url.append("&symbol=").append(enc(symbol));
        }
        if (timeframe != null) {
            url.append("&timeframe=").append(enc(timeframe));
        }
        return url.toString();
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

        try {
            return NetworkType.valueOf(v);
        } catch (Exception e) {
            return def;
        }
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
        return canonicalizeTimeframe(tf);
    }


    private List<String> resolveAvailableTimeframes(String exchange, NetworkType network) {
        String ex = normalizeExchange(exchange);
        NetworkType net = (network != null) ? network : NetworkType.TESTNET;

        try {
            MarketOverviewDto overview = marketInfoService.getOverview(ex, net);
            if (overview != null && overview.getTimeframes() != null && !overview.getTimeframes().isEmpty()) {
                List<String> canonical = canonicalizeTimeframes(overview.getTimeframes());
                if (!canonical.isEmpty()) {
                    return canonical;
                }
            }
        } catch (Exception e) {
            log.warn("⚠ Не удалось получить таймфреймы ex={} net={}: {}", ex, net, e.getMessage());
        }

        return fallbackTimeframesForExchange(ex);
    }

    private List<String> fallbackTimeframesForExchange(String exchange) {
        String ex = normalizeExchange(exchange);
        if ("BYBIT".equals(ex)) {
            return List.of("1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "12h", "1d", "1w", "1mo");
        }
        if ("BINANCE".equals(ex)) {
            return List.of("1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d", "3d", "1w", "1mo");
        }
        return DEFAULT_TIMEFRAMES;
    }

    private boolean ensureAllowedTimeframe(StrategySettings strategy, List<String> availableTimeframes) {
        if (strategy == null) return false;

        List<String> allowed = canonicalizeTimeframes(availableTimeframes);
        String current = normalizeTimeframe(strategy.getTimeframe());
        String next = chooseAllowedTimeframe(current, allowed);

        if (next == null) {
            return false;
        }

        if (!Objects.equals(current, next) || !Objects.equals(strategy.getTimeframe(), next)) {
            strategy.setTimeframe(next);
            return true;
        }

        return false;
    }

    private String chooseAllowedTimeframe(String current, List<String> availableTimeframes) {
        List<String> allowed = canonicalizeTimeframes(availableTimeframes);
        if (allowed.isEmpty()) {
            return current != null ? current : "1m";
        }

        String normalizedCurrent = normalizeTimeframe(current);
        if (normalizedCurrent != null && allowed.contains(normalizedCurrent)) {
            return normalizedCurrent;
        }

        return allowed.getFirst();
    }

    private List<String> canonicalizeTimeframes(Collection<String> raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String timeframe : raw) {
                String normalized = canonicalizeTimeframe(timeframe);
                if (normalized != null) {
                    out.add(normalized);
                }
            }
        }
        return out.isEmpty() ? new ArrayList<>(DEFAULT_TIMEFRAMES) : new ArrayList<>(out);
    }

    private String canonicalizeTimeframe(String tf) {
        if (tf == null) return null;

        String raw = tf.trim();
        if (raw.isEmpty()) return null;

        if ("M".equals(raw) || "1M".equals(raw)) return "1mo";
        if ("W".equals(raw) || "1W".equals(raw)) return "1w";
        if ("D".equals(raw) || "1D".equals(raw)) return "1d";
        if ("3D".equals(raw)) return "3d";

        String lower = raw.toLowerCase(Locale.ROOT);

        return switch (lower) {
            case "1", "1m" -> "1m";
            case "3", "3m" -> "3m";
            case "5", "5m" -> "5m";
            case "15", "15m" -> "15m";
            case "30", "30m" -> "30m";
            case "60", "1h" -> "1h";
            case "120", "2h" -> "2h";
            case "240", "4h" -> "4h";
            case "360", "6h" -> "6h";
            case "480", "8h" -> "8h";
            case "720", "12h" -> "12h";
            case "1d", "d" -> "1d";
            case "3d" -> "3d";
            case "1w", "w" -> "1w";
            case "1mo", "1mon", "1month" -> "1mo";
            default -> lower;
        };
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
        if (s != null && s.getExchangeName() != null && !s.getExchangeName().isBlank()) {
            return normalizeExchange(s.getExchangeName());
        }
        return "BINANCE";
    }

    private NetworkType resolveNetwork(String raw, StrategySettings s) {
        if (raw != null && !raw.isBlank()) return parseNetworkOrDefault(raw, NetworkType.TESTNET);
        if (s != null && s.getNetworkType() != null) return s.getNetworkType();
        return NetworkType.TESTNET;
    }

    private String enc(String s) {
        return URLEncoder.encode(String.valueOf(s), StandardCharsets.UTF_8);
    }

    private static String safe(String s) {
        if (s == null) return "";
        String x = s.trim();
        return x.length() > 200 ? x.substring(0, 200) : x;
    }

    private static String normalizeUpperNullable(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty()) return null;
        return v.toUpperCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private AccountBalanceSnapshot.AssetBalance findBalance(AccountBalanceSnapshot snap, String asset) {
        if (snap == null || asset == null || asset.isBlank()) return null;

        String a = asset.trim().toUpperCase(Locale.ROOT);

        try {
            Method m = snap.getClass().getMethod("getBalances");
            Object r = m.invoke(snap);
            if (r instanceof Map<?, ?> map) {
                Object v = map.get(a);
                if (v instanceof AccountBalanceSnapshot.AssetBalance ab) return ab;
            }
        } catch (Exception ignored) {
        }

        try {
            Method m = snap.getClass().getMethod("getBalancesByAsset");
            Object r = m.invoke(snap);
            if (r instanceof Map<?, ?> map) {
                Object v = map.get(a);
                if (v instanceof AccountBalanceSnapshot.AssetBalance ab) return ab;
            }
        } catch (Exception ignored) {
        }

        try {
            Field f = snap.getClass().getDeclaredField("balances");
            f.setAccessible(true);
            Object r = f.get(snap);
            if (r instanceof Map<?, ?> map) {
                Object v = map.get(a);
                if (v instanceof AccountBalanceSnapshot.AssetBalance ab) return ab;
            }
        } catch (Exception ignored) {
        }

        try {
            Field f = snap.getClass().getDeclaredField("balancesByAsset");
            f.setAccessible(true);
            Object r = f.get(snap);
            if (r instanceof Map<?, ?> map) {
                Object v = map.get(a);
                if (v instanceof AccountBalanceSnapshot.AssetBalance ab) return ab;
            }
        } catch (Exception ignored) {
        }

        try {
            Method m = snap.getClass().getMethod("getBalance", String.class);
            Object v = m.invoke(snap, a);
            if (v instanceof AccountBalanceSnapshot.AssetBalance ab) return ab;
        } catch (Exception ignored) {
        }

        try {
            Method m = snap.getClass().getMethod("getAssetBalance", String.class);
            Object v = m.invoke(snap, a);
            if (v instanceof AccountBalanceSnapshot.AssetBalance ab) return ab;
        } catch (Exception ignored) {
        }

        try {
            return snap.getSelectedBalance();
        } catch (Exception ignored) {
            return null;
        }
    }
}


