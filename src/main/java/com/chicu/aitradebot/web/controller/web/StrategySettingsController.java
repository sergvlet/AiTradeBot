package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.exchange.model.ApiKeyDiagnostics;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import com.chicu.aitradebot.exchange.service.RealFeeService;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.cache.StrategySettingsCache;
import com.chicu.aitradebot.strategy.fibonacci.FibonacciGridStrategySettings;
import com.chicu.aitradebot.strategy.fibonacci.FibonacciGridStrategySettingsService;
import com.chicu.aitradebot.strategy.rsie.RsiEmaStrategySettings;
import com.chicu.aitradebot.strategy.rsie.RsiEmaStrategySettingsService;
import com.chicu.aitradebot.strategy.scalping.ScalpingStrategySettings;
import com.chicu.aitradebot.strategy.scalping.ScalpingStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Controller
@RequiredArgsConstructor
@RequestMapping("/strategies/{type}/config")
public class StrategySettingsController {

    private final StrategySettingsService strategySettingsService;
    private final ExchangeSettingsService exchangeSettingsService;
    private final RealFeeService realFeeService;
    private final ExchangeClientFactory clientFactory;

    private final ScalpingStrategySettingsService scalpingSettingsService;
    private final RsiEmaStrategySettingsService rsiEmaSettingsService;
    private final FibonacciGridStrategySettingsService fibonacciSettingsService;

    /** 🔥 V4 — глобальный инвалидатор кэша стратегий */
    private final StrategySettingsCache settingsCache;

    private static final List<String> DEFAULT_TIMEFRAMES = List.of(
            "1s", "5s", "15s",
            "1m", "3m", "5m", "15m", "30m",
            "1h", "4h", "1d"
    );

    private static final List<String> AVAILABLE_EXCHANGES =
            List.of("BINANCE", "BYBIT", "OKX");

    // =====================================================
    // GET — ОТКРЫТЬ НАСТРОЙКИ
    // =====================================================
    @GetMapping
    public String openSettings(
            @PathVariable("type") String type,
            @RequestParam("chatId") long chatId,
            @RequestParam(value = "exchange", required = false) String exchangeParam,
            @RequestParam(value = "network", required = false) NetworkType networkParam,
            @RequestParam(value = "tab", required = false) String tab,
            Model model
    ) {

        StrategyType strategyType = StrategyType.valueOf(type);

        StrategySettings strategy = strategySettingsService.getOrCreate(chatId, strategyType);
        ensureConcreteSettingsExist(strategyType, chatId);
        pullConcreteIntoUnified(strategyType, chatId, strategy);
        strategySettingsService.save(strategy);

        List<ExchangeSettings> exchanges = exchangeSettingsService.findAllByChatId(chatId);

        String selectedExchange = exchangeParam != null ? exchangeParam :
                strategy.getExchangeName() != null ? strategy.getExchangeName() :
                        !exchanges.isEmpty() ? exchanges.get(0).getExchange() : "BINANCE";

        NetworkType selectedNetwork = networkParam != null ? networkParam :
                strategy.getNetworkType() != null ? strategy.getNetworkType() :
                        !exchanges.isEmpty() ? exchanges.get(0).getNetwork() : NetworkType.TESTNET;

        strategy.setExchangeName(selectedExchange);
        strategy.setNetworkType(selectedNetwork);
        strategySettingsService.save(strategy);

        ExchangeSettings exchangeSettings =
                exchangeSettingsService.getOrCreate(chatId, selectedExchange, selectedNetwork);

        ApiKeyDiagnostics diagnostics = null;
        boolean connectionOk = false;

        if (exchangeSettings.hasKeys()) {
            diagnostics = exchangeSettingsService.testConnectionDetailed(exchangeSettings);
            connectionOk = diagnostics != null && diagnostics.isOk();
        }

        double usdtBalance = 0.0;
        List<String> availableTimeframes = new ArrayList<>(DEFAULT_TIMEFRAMES);

        try {
            ExchangeClient client = clientFactory.get(selectedExchange, selectedNetwork);

            var bal = client.getBalance(chatId, "USDT", selectedNetwork);
            if (bal != null) usdtBalance = bal.free();

            var tf = client.getAvailableTimeframes();
            if (tf != null && !tf.isEmpty()) availableTimeframes = tf;

        } catch (Exception e) {
            log.warn("Ошибка загрузки данных биржи: {}", e.getMessage());
        }

        if (tab == null || tab.isBlank()) tab = "network";

        model.addAttribute("page", "strategies/settings");
        model.addAttribute("pageTitle", "Настройки стратегии");
        model.addAttribute("chatId", chatId);
        model.addAttribute("type", strategyType);
        model.addAttribute("strategy", strategy);
        model.addAttribute("activeTab", tab);
        model.addAttribute("availableExchanges", AVAILABLE_EXCHANGES);
        model.addAttribute("selectedExchange", selectedExchange);
        model.addAttribute("selectedNetwork", selectedNetwork);
        model.addAttribute("exchangeSettings", exchangeSettings);
        model.addAttribute("diagnostics", diagnostics);
        model.addAttribute("connectionOk", connectionOk);
        model.addAttribute("usdtBalance", usdtBalance);
        model.addAttribute("availableTimeframes", availableTimeframes);

        return "layout/app";
    }

    // =====================================================
    // POST — СОХРАНИТЬ НАСТРОЙКИ
    // =====================================================
    @PostMapping
    public String saveSettings(
            @PathVariable("type") String type,
            @RequestParam("chatId") long chatId,
            @RequestParam(value = "tab", required = false) String tab,
            @ModelAttribute("strategy") StrategySettings form,
            @RequestParam Map<String, String> params
    ) {

        StrategyType strategyType = StrategyType.valueOf(type);

        String exchangeName = params.get("exchange");
        NetworkType networkType = NetworkType.valueOf(params.get("network"));

        if (tab == null || tab.isBlank()) tab = "general";

        StrategySettings s = strategySettingsService.getOrCreate(chatId, strategyType);

        s.setSymbol(form.getSymbol());
        s.setTimeframe(form.getTimeframe());
        s.setCachedCandlesLimit(form.getCachedCandlesLimit());
        s.setCapitalUsd(form.getCapitalUsd());
        s.setCommissionPct(form.getCommissionPct());
        s.setRiskPerTradePct(form.getRiskPerTradePct());
        s.setDailyLossLimitPct(form.getDailyLossLimitPct());
        s.setTakeProfitPct(form.getTakeProfitPct());
        s.setStopLossPct(form.getStopLossPct());
        s.setReinvestProfit(form.isReinvestProfit());
        s.setLeverage(form.getLeverage());
        s.setExchangeName(exchangeName);
        s.setNetworkType(networkType);

        s = strategySettingsService.save(s);

        syncConcreteStrategySettings(strategyType, chatId, s);

        // 🔥 V4 — ИНВАЛИДАЦИЯ КЭША СТРАТЕГИИ
        settingsCache.invalidate(chatId, strategyType);

        ExchangeSettings ex =
                exchangeSettingsService.getOrCreate(chatId, exchangeName, networkType);

        ex.setApiKey(params.get("apiKey"));
        ex.setApiSecret(params.get("apiSecret"));
        ex.setPassphrase(params.get("passphrase"));

        if (ex.hasKeys()) {
            ApiKeyDiagnostics diag = exchangeSettingsService.testConnectionDetailed(ex);
            ex.setEnabled(diag != null && diag.isOk());
        }

        exchangeSettingsService.save(ex);

        return "redirect:/strategies/" + type + "/config"
               + "?chatId=" + chatId
               + "&exchange=" + exchangeName
               + "&network=" + networkType
               + "&tab=" + tab;
    }

    // =====================================================
    // остальная логика (без изменений)
    // =====================================================
    private void ensureConcreteSettingsExist(StrategyType type, long chatId) {
        switch (type) {
            case SCALPING -> scalpingSettingsService.getOrCreate(chatId);
            case RSI_EMA -> rsiEmaSettingsService.getOrCreate(chatId);
            case FIBONACCI_GRID -> fibonacciSettingsService.getOrCreate(chatId);
        }
    }

    private void pullConcreteIntoUnified(StrategyType type, long chatId, StrategySettings s) {
        switch (type) {
            case SCALPING -> {
                ScalpingStrategySettings t = scalpingSettingsService.getOrCreate(chatId);
                s.setSymbol(t.getSymbol());
                s.setTimeframe(t.getTimeframe());
                s.setCachedCandlesLimit(t.getCachedCandlesLimit());
                s.setTakeProfitPct(BigDecimal.valueOf(t.getTakeProfitPct()));
                s.setStopLossPct(BigDecimal.valueOf(t.getStopLossPct()));
                s.setNetworkType(t.getNetworkType());
            }
            case RSI_EMA -> {
                RsiEmaStrategySettings t = rsiEmaSettingsService.getOrCreate(chatId);
                s.setSymbol(t.getSymbol());
                s.setTimeframe(t.getTimeframe());
                s.setCachedCandlesLimit(t.getCachedCandlesLimit());
                s.setCapitalUsd(BigDecimal.valueOf(t.getCapitalUsd()));
                s.setCommissionPct(BigDecimal.valueOf(t.getCommissionPct()));
                s.setRiskPerTradePct(BigDecimal.valueOf(t.getRiskPerTradePct()));
                s.setDailyLossLimitPct(BigDecimal.valueOf(t.getDailyLossLimitPct()));
                s.setTakeProfitPct(BigDecimal.valueOf(t.getTakeProfitPct()));
                s.setStopLossPct(BigDecimal.valueOf(t.getStopLossPct()));
                s.setReinvestProfit(t.isReinvestProfit());
                s.setLeverage(t.getLeverage());
                s.setNetworkType(t.getNetworkType());
            }
            case FIBONACCI_GRID -> {
                FibonacciGridStrategySettings t = fibonacciSettingsService.getOrCreate(chatId);
                s.setSymbol(t.getSymbol());
                s.setTimeframe(t.getTimeframe());
                s.setCachedCandlesLimit(t.getCandleLimit());
                s.setNetworkType(t.getNetworkType());
            }
        }
    }

    private void syncConcreteStrategySettings(StrategyType type, long chatId, StrategySettings s) {
        switch (type) {
            case SCALPING -> {
                ScalpingStrategySettings t = scalpingSettingsService.getOrCreate(chatId);
                t.setSymbol(s.getSymbol());
                t.setTimeframe(s.getTimeframe());
                t.setCachedCandlesLimit(s.getCachedCandlesLimit());
                if (s.getTakeProfitPct() != null) t.setTakeProfitPct(s.getTakeProfitPct().doubleValue());
                if (s.getStopLossPct() != null) t.setStopLossPct(s.getStopLossPct().doubleValue());
                t.setNetworkType(s.getNetworkType());
                scalpingSettingsService.save(t);
            }
            case RSI_EMA -> {
                RsiEmaStrategySettings t = rsiEmaSettingsService.getOrCreate(chatId);
                t.setSymbol(s.getSymbol());
                t.setTimeframe(s.getTimeframe());
                t.setCachedCandlesLimit(s.getCachedCandlesLimit());
                if (s.getCapitalUsd() != null) t.setCapitalUsd(s.getCapitalUsd().doubleValue());
                if (s.getCommissionPct() != null) t.setCommissionPct(s.getCommissionPct().doubleValue());
                if (s.getRiskPerTradePct() != null) t.setRiskPerTradePct(s.getRiskPerTradePct().doubleValue());
                if (s.getDailyLossLimitPct() != null) t.setDailyLossLimitPct(s.getDailyLossLimitPct().doubleValue());
                if (s.getTakeProfitPct() != null) t.setTakeProfitPct(s.getTakeProfitPct().doubleValue());
                if (s.getStopLossPct() != null) t.setStopLossPct(s.getStopLossPct().doubleValue());
                t.setReinvestProfit(s.isReinvestProfit());
                t.setLeverage(s.getLeverage());
                t.setNetworkType(s.getNetworkType());
                rsiEmaSettingsService.save(t);
            }
            case FIBONACCI_GRID -> {
                FibonacciGridStrategySettings t = fibonacciSettingsService.getOrCreate(chatId);
                t.setSymbol(s.getSymbol());
                t.setTimeframe(s.getTimeframe());
                t.setCandleLimit(s.getCachedCandlesLimit());
                t.setNetworkType(s.getNetworkType());
                fibonacciSettingsService.save(t);
            }
        }
    }
}
