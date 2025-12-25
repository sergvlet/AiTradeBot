package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.account.AccountBalanceService;
import com.chicu.aitradebot.account.AccountBalanceSnapshot;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.exchange.model.ApiKeyDiagnostics;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.cache.StrategySettingsCache;
import com.chicu.aitradebot.strategy.rsie.RsiEmaStrategySettings;
import com.chicu.aitradebot.strategy.rsie.RsiEmaStrategySettingsService;
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
        // exchange / network — source of truth
        // =====================================================
        String exchange = Optional.ofNullable(request.getParameter("exchange"))
                .orElse("BINANCE");

        NetworkType network = Optional.ofNullable(request.getParameter("network"))
                .map(NetworkType::valueOf)
                .orElse(NetworkType.TESTNET);

        // =====================================================
        // StrategySettings — UI STATE (SOURCE OF TRUTH)
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
        // BALANCE SNAPSHOT (READ-ONLY)
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
        // 🔥 ВЫБРАННЫЙ АКТИВ — ТОЛЬКО ИЗ StrategySettings
        // =====================================================
        String selectedAsset = strategy.getAccountAsset();

        // fallback, если ещё не сохранён
        if (selectedAsset == null || selectedAsset.isBlank()) {
            // если в snapshot есть какой-то "текущий" актив — используй его
            // (если такого метода нет — просто оставь null, UI покажет дефолт)
            try {
                selectedAsset = balance.getSelectedAsset(); // если у тебя такого метода нет — убери эту строку
            } catch (Exception ignored) {
                // ничего, selectedAsset останется null
            }
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

        // =====================================================
        // BALANCE → UI (НЕ источник истины)
        // =====================================================
        model.addAttribute("availableAssets", balance.getAvailableAssets());

        // ❗ выбранный актив — что сохранили в StrategySettings
        model.addAttribute("selectedAsset", selectedAsset);

        // ✅ безопасно: у snapshot есть только "selectedFreeBalance"
        model.addAttribute("availableBalance", balance.getSelectedFreeBalance());
        model.addAttribute("balanceConnectionOk", balance.isConnectionOk());

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
        // 🔥 КРИТИЧЕСКИЙ FIX — POST ГАРАНТИРУЕТ НАЛИЧИЕ ЗАПИСИ
        // =====================================================
        StrategySettings s =
                strategySettingsService
                        .findLatest(chatId, strategyType, exchange, network)
                        .orElseGet(() -> {
                            log.warn(
                                    "⚠️ StrategySettings not found → create new (chatId={} type={} ex={} net={})",
                                    chatId, strategyType, exchange, network
                            );
                            return strategySettingsService.getOrCreate(
                                    chatId, strategyType, exchange, network
                            );
                        });

        log.info(
                "📄 Loaded settings id={} asset={} symbol={} tf={}",
                s.getId(), s.getAccountAsset(), s.getSymbol(), s.getTimeframe()
        );

        // =====================================================
        // 💰 accountAsset — ЕДИНСТВЕННОЕ МЕСТО
        // =====================================================
        String accountAsset = params.get("accountAsset");
        if (accountAsset != null && !accountAsset.isBlank()) {
            log.info("💰 accountAsset: {} -> {}", s.getAccountAsset(), accountAsset);
            s.setAccountAsset(accountAsset);
        }

        // =====================================================
        // 🔀 SAVE BY SCOPE
        // =====================================================
        switch (saveScope) {

            case "network" -> {
                s.setExchangeName(exchange);
                s.setNetworkType(network);
                strategySettingsService.save(s);
                exchangeSettingsService.saveNetwork(chatId, exchange, network);
            }

            case "trade" -> {
                s.setSymbol(form.getSymbol());
                s.setTimeframe(form.getTimeframe());
                s.setCachedCandlesLimit(form.getCachedCandlesLimit());
                strategySettingsService.save(s);
            }

            case "risk" -> {
                s.setRiskPerTradePct(form.getRiskPerTradePct());
                s.setDailyLossLimitPct(form.getDailyLossLimitPct());
                s.setTakeProfitPct(form.getTakeProfitPct());
                s.setStopLossPct(form.getStopLossPct());
                strategySettingsService.save(s);
            }

            case "general" -> {
                boolean reinvest = params.containsKey("reinvestProfit");
                s.setReinvestProfit(reinvest);

                BigDecimal maxExposureUsd = parseBigDecimalOrNull(params.get("maxExposureUsd"));
                Integer maxExposurePct   = parseIntOrNull(params.get("maxExposurePct"));

                if (maxExposureUsd != null && maxExposureUsd.signum() <= 0) {
                    maxExposureUsd = null;
                }
                if (maxExposurePct != null && (maxExposurePct <= 0 || maxExposurePct > 100)) {
                    maxExposurePct = null;
                }

                s.setMaxExposureUsd(maxExposureUsd);
                s.setMaxExposurePct(maxExposurePct);

                strategySettingsService.save(s);
            }

            case "advanced" -> {
                if (form.getAdvancedControlMode() != null) {
                    s.setAdvancedControlMode(form.getAdvancedControlMode());
                    strategySettingsService.save(s);
                }
            }

            default -> log.warn("⚠️ Unknown saveScope='{}'", saveScope);
        }

        // =====================================================
        // 🔄 POST SAVE
        // =====================================================
        syncRsiEmaFromUnified(strategyType, chatId, s);
        settingsCache.invalidate(chatId, strategyType);

        log.info("✅ SAVE SETTINGS DONE id={} scope={}", s.getId(), saveScope);

        return "redirect:/strategies/" + type +
               "/config?chatId=" + chatId +
               "&exchange=" + exchange +
               "&network=" + network.name() +
               "&tab=" + saveScope;
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
