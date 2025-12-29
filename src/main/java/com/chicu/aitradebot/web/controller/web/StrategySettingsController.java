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
import java.util.Optional;

@Slf4j
@Controller
@RequiredArgsConstructor
@RequestMapping("/strategies/{type}/config")
public class StrategySettingsController {

    private final StrategySettingsService strategySettingsService;
    private final ExchangeSettingsService exchangeSettingsService;
    private final RsiEmaStrategySettingsService rsiEmaSettingsService;
    private final StrategySettingsCache settingsCache;
    private final AccountBalanceService accountBalanceService;
    private final MarketSymbolService marketSymbolService;
    private final StrategyAdvancedRegistry strategyAdvancedRegistry;
    private static final List<String> DEFAULT_TIMEFRAMES = List.of(
            "1s","5s","15s","1m","3m","5m","15m","30m","1h","4h","1d"
    );

    private static final List<String> AVAILABLE_EXCHANGES =
            List.of("BINANCE","BYBIT","OKX");


    // =====================================================
    // GET — ОТКРЫТЬ НАСТРОЙКИ
    // =====================================================
    @GetMapping
    public String openSettings(
            @PathVariable("type") String type,
            @RequestParam("chatId") long chatId,
            @RequestParam(value = "tab", required = false) String tab,
            HttpServletRequest request,
            Model model
    ) {

        StrategyType strategyType = StrategyType.valueOf(type);

        // =====================================================
        // exchange / network
        // =====================================================
        String exchange = Optional.ofNullable(request.getParameter("exchange"))
                .orElse("BINANCE");

        NetworkType network = Optional.ofNullable(request.getParameter("network"))
                .map(NetworkType::valueOf)
                .orElse(NetworkType.TESTNET);

        // =====================================================
        // StrategySettings (unified)
        // =====================================================
        StrategySettings strategy =
                strategySettingsService
                        .findLatest(chatId, strategyType, exchange, network)
                        .orElseGet(() ->
                                strategySettingsService.getOrCreate(
                                        chatId, strategyType, exchange, network
                                )
                        );

        pullRsiEmaIntoUnifiedIfEmpty(strategyType, chatId, strategy);

        // =====================================================
        // BALANCE SNAPSHOT
        // =====================================================
        AccountBalanceSnapshot balance =
                accountBalanceService.getSnapshot(
                        chatId, strategyType, exchange, network
                );

        // =====================================================
        // Exchange + diagnostics
        // =====================================================
        ExchangeSettings exchangeSettings =
                exchangeSettingsService.getOrCreate(chatId, exchange, network);

        ApiKeyDiagnostics diagnostics =
                exchangeSettings.hasKeys()
                        ? exchangeSettingsService.testConnectionDetailed(exchangeSettings)
                        : null;

        // =====================================================
        // SELECTED ASSET
        // =====================================================
        String selectedAsset = strategy.getAccountAsset();
        if (selectedAsset == null || selectedAsset.isBlank()) {
            selectedAsset = balance.getSelectedAsset();
        }

        // =====================================================
        // SYMBOL INFO
        // =====================================================
        SymbolDescriptor symbolInfo = null;

        if (strategy.getSymbol() != null && !strategy.getSymbol().isBlank()) {
            try {
                symbolInfo = marketSymbolService.getSymbolInfo(
                        exchange,
                        network,
                        selectedAsset,
                        strategy.getSymbol()
                );
            } catch (Exception e) {
                log.warn("⚠ Не удалось получить symbolInfo symbol={}: {}",
                        strategy.getSymbol(), e.getMessage());
            }
        }

        // =====================================================
        // FEES
        // =====================================================
        AccountFees accountFees = null;
        if (exchangeSettings.hasKeys() && diagnostics != null && diagnostics.isOk()) {
            try {
                accountFees = accountBalanceService.getAccountFees(chatId, exchange, network);
            } catch (Exception e) {
                log.warn("⚠ Не удалось получить комиссии: {}", e.getMessage());
            }
        }

        // =====================================================
        // 🔥 STRATEGY ADVANCED (DYNAMIC HTML)
        // =====================================================
        String strategyAdvancedHtml = null;

        StrategyAdvancedRenderer advancedRenderer =
                strategyAdvancedRegistry.get(strategyType);

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
        // MODEL
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
        model.addAttribute("diagnostics", diagnostics);
        model.addAttribute("connectionOk", diagnostics != null && diagnostics.isOk());

        model.addAttribute("availableAssets", balance.getAvailableAssets());
        model.addAttribute("selectedAsset", selectedAsset);
        model.addAttribute("availableBalance", balance.getSelectedFreeBalance());
        model.addAttribute("balanceConnectionOk", balance.isConnectionOk());

        model.addAttribute("accountFees", accountFees);
        model.addAttribute("symbolInfo", symbolInfo);

        // 🔥 ВАЖНОЕ
        model.addAttribute("strategyAdvancedHtml", strategyAdvancedHtml);

        return "layout/app";
    }

    // =====================================================
    // POST — СОХРАНЕНИЕ (FIXED)
    // =====================================================
    @PostMapping
    public String saveSettings(
            @PathVariable("type") String type,
            @RequestParam("chatId") long chatId,
            @RequestParam("saveScope") String saveScope,
            @RequestParam Map<String, String> params,
            @ModelAttribute("strategy") StrategySettings form
    ) {

        StrategyType strategyType = StrategyType.valueOf(type);

        String exchange = params.getOrDefault("exchange", "BINANCE");
        NetworkType network =
                NetworkType.valueOf(params.getOrDefault("network", "TESTNET"));

        log.info(
                "💾 SAVE SETTINGS START type={} chatId={} scope={} ex={} net={}",
                strategyType, chatId, saveScope, exchange, network
        );
        log.debug("📥 RAW PARAMS: {}", params);

        // =====================================================
        // 🔥 POST гарантирует наличие записи
        // =====================================================
        StrategySettings s =
                strategySettingsService
                        .findLatest(chatId, strategyType, exchange, network)
                        .orElseGet(() -> strategySettingsService.getOrCreate(
                                chatId, strategyType, exchange, network
                        ));

        // =====================================================
        // 🔐 GLOBAL: advancedControlMode (применяется сразу)
        // =====================================================
        if (params.containsKey("advancedControlMode")) {
            try {
                s.setAdvancedControlMode(
                        AdvancedControlMode.valueOf(params.get("advancedControlMode"))
                );
            } catch (Exception e) {
                log.warn("Invalid advancedControlMode: {}", params.get("advancedControlMode"));
            }
        }

        // =====================================================
        // 💰 accountAsset — единая точка
        // =====================================================
        String accountAsset = params.get("accountAsset");
        if (accountAsset != null && !accountAsset.isBlank()) {
            s.setAccountAsset(accountAsset);
        }

        String redirect;

        switch (saveScope) {

            case "network":
                s.setExchangeName(exchange);
                s.setNetworkType(network);
                strategySettingsService.save(s);
                exchangeSettingsService.saveNetwork(chatId, exchange, network);
                break;

            case "trade":
                s.setSymbol(form.getSymbol());
                s.setTimeframe(form.getTimeframe());
                s.setCachedCandlesLimit(form.getCachedCandlesLimit());

                // 🔥 EXECUTION POLICY
                Integer maxOpenOrders   = parseIntOrNull(params.get("maxOpenOrders"));
                Integer cooldownSeconds = parseIntOrNull(params.get("cooldownSeconds"));

                // maxOpenOrders: null / <=0 → без ограничения
                s.setMaxOpenOrders(
                        (maxOpenOrders != null && maxOpenOrders > 0)
                                ? maxOpenOrders
                                : null
                );

                // cooldownSeconds: null / 0 / <0 → без паузы
                s.setCooldownSeconds(
                        (cooldownSeconds != null && cooldownSeconds > 0)
                                ? cooldownSeconds
                                : null
                );

                strategySettingsService.save(s);
                break;




            case "risk":
                // 🔐 Risk limits — ТОЛЬКО через policy
                strategySettingsService.updateRiskFromUi(
                        chatId,
                        strategyType,
                        exchange,
                        network,
                        parseBigDecimalOrNull(params.get("dailyLossLimitPct")),
                        parseBigDecimalOrNull(params.get("riskPerTradePct"))
                );

                // 🔄 перезагружаем актуальное состояние
                StrategySettings refreshed =
                        strategySettingsService
                                .findLatest(chatId, strategyType, exchange, network)
                                .orElseThrow();

                // TP / SL — напрямую
                BigDecimal stopLossPct  = parseBigDecimalOrNull(params.get("stopLossPct"));
                BigDecimal takeProfitPct = parseBigDecimalOrNull(params.get("takeProfitPct"));

                boolean changed = false;

                if (stopLossPct != null) {
                    refreshed.setStopLossPct(stopLossPct);
                    changed = true;
                }
                if (takeProfitPct != null) {
                    refreshed.setTakeProfitPct(takeProfitPct);
                    changed = true;
                }

                if (changed) {
                    strategySettingsService.save(refreshed);
                }

                break;

            case "general":
                s.setReinvestProfit(params.containsKey("reinvestProfit"));

                BigDecimal maxExposureUsd =
                        parseBigDecimalOrNull(params.get("maxExposureUsd"));
                Integer maxExposurePct =
                        parseIntOrNull(params.get("maxExposurePct"));

                if (maxExposureUsd != null && maxExposureUsd.signum() <= 0) {
                    maxExposureUsd = null;
                }
                if (maxExposurePct != null &&
                    (maxExposurePct <= 0 || maxExposurePct > 100)) {
                    maxExposurePct = null;
                }

                s.setMaxExposureUsd(maxExposureUsd);
                s.setMaxExposurePct(maxExposurePct);
                strategySettingsService.save(s);
                break;

            case "advanced": {

                // 1️⃣ текущий режим ДО изменений
                AdvancedControlMode currentMode = s.getAdvancedControlMode();

                // 2️⃣ strategy-specific advanced
                StrategyAdvancedRenderer renderer =
                        strategyAdvancedRegistry.get(strategyType);

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

                } else if (currentMode == AdvancedControlMode.AI) {
                    log.info(
                            "🔒 Advanced params ignored (AI mode) chatId={} strategy={}",
                            chatId, strategyType
                    );
                }

                // 3️⃣ смена режима (GLOBAL)
                String modeRaw = params.get("advancedControlMode");
                if (modeRaw != null) {
                    try {
                        s.setAdvancedControlMode(
                                AdvancedControlMode.valueOf(modeRaw)
                        );
                    } catch (IllegalArgumentException e) {
                        log.warn("⚠️ Invalid advancedControlMode='{}'", modeRaw);
                    }
                }

                // 4️⃣ save
                strategySettingsService.save(s);
                break;
            }





            default:
                log.warn("⚠️ Unknown saveScope='{}'", saveScope);
        }

        // =====================================================
        // 🔄 POST SAVE
        // =====================================================
        syncRsiEmaFromUnified(strategyType, chatId, s);
        settingsCache.invalidate(chatId, strategyType);

        redirect = buildRedirect(type, chatId, exchange, network, saveScope);

        log.info("✅ SAVE SETTINGS DONE id={} scope={}", s.getId(), saveScope);
        return redirect;
    }

    private String buildRedirect(
            String type,
            long chatId,
            String exchange,
            NetworkType network,
            String tab
    ) {
        return "redirect:/strategies/" + type +
               "/config?chatId=" + chatId +
               "&exchange=" + exchange +
               "&network=" + network.name() +
               "&tab=" + tab;
    }

    // =====================================================
    // AJAX — СМЕНА АКТИВА (FIXED)
    // =====================================================
    @PostMapping("/asset")
    @ResponseBody
    public AccountBalanceSnapshot changeAccountAsset(
            @PathVariable("type") String type,
            @RequestParam("chatId") long chatId,
            @RequestParam("exchange") String exchange,
            @RequestParam("network") NetworkType network,
            @RequestParam("asset") String asset
    ) {

        StrategyType strategyType = StrategyType.valueOf(type);

        // 🔥 FIX — тоже findLatest
        StrategySettings settings =
                strategySettingsService
                        .findLatest(chatId, strategyType, exchange, network)
                        .orElseThrow(() ->
                                new IllegalStateException("StrategySettings not found for asset change"));

        settings.setAccountAsset(asset);
        strategySettingsService.save(settings);

        settingsCache.invalidate(chatId, strategyType);

        return accountBalanceService.getSnapshot(
                chatId, strategyType, exchange, network
        );
    }

    // =====================================================
    // RSI EMA legacy
    // =====================================================
    private void pullRsiEmaIntoUnifiedIfEmpty(
            StrategyType type,
            long chatId,
            StrategySettings s
    ) {
        if (type != StrategyType.RSI_EMA) return;
        if (s.getSymbol() != null && s.getTimeframe() != null) return;

        RsiEmaStrategySettings t = rsiEmaSettingsService.getOrCreate(chatId);
        s.setSymbol(t.getSymbol());
        s.setTimeframe(t.getTimeframe());
        s.setCachedCandlesLimit(t.getCachedCandlesLimit());
        s.setNetworkType(t.getNetworkType());

        strategySettingsService.save(s);
    }

    private void syncRsiEmaFromUnified(
            StrategyType type,
            long chatId,
            StrategySettings s
    ) {
        if (type != StrategyType.RSI_EMA) return;

        RsiEmaStrategySettings t = rsiEmaSettingsService.getOrCreate(chatId);
        t.setSymbol(s.getSymbol());
        t.setTimeframe(s.getTimeframe());
        t.setCachedCandlesLimit(s.getCachedCandlesLimit());
        t.setNetworkType(s.getNetworkType());
        rsiEmaSettingsService.save(t);
    }

    private BigDecimal parseBigDecimalOrNull(String v) {
        try {
            return v == null ? null : new BigDecimal(v.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseIntOrNull(String v) {
        try {
            return v == null ? null : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
