package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.exchange.model.BinanceConnectionStatus;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import com.chicu.aitradebot.exchange.service.RealFeeService;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@Controller
@RequiredArgsConstructor
@RequestMapping("/strategies/{type}/unified-settings")
public class StrategySettingsController {

    private final StrategySettingsService strategySettingsService;
    private final ExchangeSettingsService exchangeSettingsService;
    private final RealFeeService realFeeService;
    private final ExchangeClientFactory clientFactory;

    private static final List<String> DEFAULT_TIMEFRAMES = List.of(
            "1s", "5s", "15s",
            "1m", "3m", "5m", "15m", "30m",
            "1h", "4h", "1d"
    );

    // =========================================================================
    // GET — ОТКРЫТИЕ СТРАНИЦЫ
    // =========================================================================
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
        List<String> availableExchanges = List.of("BINANCE", "BYBIT", "OKX");
        List<ExchangeSettings> userExchanges = exchangeSettingsService.findAllByChatId(chatId);

        // ---------------------- Биржа -------------------------
        String selectedExchange = exchangeParam != null
                ? exchangeParam
                : (strategy.getExchangeName() != null
                ? strategy.getExchangeName()
                : userExchanges.stream().findFirst().map(ExchangeSettings::getExchange).orElse("BINANCE"));

        // ---------------------- Сеть ---------------------------
        NetworkType selectedNetwork = networkParam != null
                ? networkParam
                : (strategy.getNetworkType() != null
                ? strategy.getNetworkType()
                : userExchanges.stream().findFirst().map(ExchangeSettings::getNetwork).orElse(NetworkType.TESTNET));

        // ---------------------- ExchangeSettings ----------------
        ExchangeSettings exchangeSettings =
                exchangeSettingsService.getOrCreate(chatId, selectedExchange, selectedNetwork);

        boolean hasKeys =
                exchangeSettings.getApiKey() != null &&
                !exchangeSettings.getApiKey().isBlank() &&
                exchangeSettings.getApiSecret() != null &&
                !exchangeSettings.getApiSecret().isBlank();

        BinanceConnectionStatus diagnostics = null;
        boolean connectionOk = false;

        if (selectedExchange.equalsIgnoreCase("BINANCE") && hasKeys) {
            diagnostics = exchangeSettingsService.testConnectionDetailed(exchangeSettings);
            if (diagnostics != null) connectionOk = diagnostics.isOk();
        } else {
            connectionOk = hasKeys && exchangeSettingsService.testConnection(exchangeSettings);
        }

        // ---------------------- Баланс ------------------------
        double usdtBalance = 0.0;
        List<String> availableTimeframes = new ArrayList<>(DEFAULT_TIMEFRAMES);

        try {
            ExchangeClient client = clientFactory.get(selectedExchange, selectedNetwork);

            var bal = client.getBalance(chatId, "USDT", selectedNetwork);
            if (bal != null) usdtBalance = bal.free();

            List<String> fromClient = client.getAvailableTimeframes();
            if (fromClient != null && !fromClient.isEmpty()) availableTimeframes = fromClient;

        } catch (Exception e) {
            log.warn("Не удалось загрузить баланс/таймфреймы: {}", e.getMessage());
        }

        // ---------------------- Активная вкладка ----------------
        if (tab == null || tab.isBlank()) tab = "network";
        model.addAttribute("activeTab", tab);

        // ---------------------- MODEL --------------------------
        model.addAttribute("chatId", chatId);
        model.addAttribute("type", strategyType);
        model.addAttribute("strategy", strategy);

        model.addAttribute("availableExchanges", availableExchanges);
        model.addAttribute("selectedExchange", selectedExchange);
        model.addAttribute("selectedNetwork", selectedNetwork);

        model.addAttribute("exchangeSettings", exchangeSettings);
        model.addAttribute("connectionOk", connectionOk);
        model.addAttribute("diagnostics", diagnostics);

        model.addAttribute("usdtBalance", usdtBalance);
        model.addAttribute("availableTimeframes", availableTimeframes);

        model.addAttribute("dynamicFields", Map.of());

        return "strategies/unified-settings";
    }

    // =========================================================================
    // POST — СОХРАНЕНИЕ НАСТРОЕК
    // =========================================================================
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

        // -------------- Активная вкладка после POST --------------
        if (tab == null || tab.isBlank()) tab = "general";

        // -------------- Сохраняем StrategySettings ----------------
        StrategySettings s = strategySettingsService.getOrCreate(chatId, strategyType);

        s.setSymbol(form.getSymbol());
        s.setTimeframe(form.getTimeframe());
        s.setCachedCandlesLimit(form.getCachedCandlesLimit());

        s.setCapitalUsd(form.getCapitalUsd());
        s.setCommissionPct(form.getCommissionPct());
        s.setReinvestProfit(form.isReinvestProfit());

        s.setRiskPerTradePct(form.getRiskPerTradePct());
        s.setDailyLossLimitPct(form.getDailyLossLimitPct());
        s.setLeverage(form.getLeverage());

        s.setTakeProfitPct(form.getTakeProfitPct());
        s.setStopLossPct(form.getStopLossPct());

        s.setExchangeName(exchangeName);
        s.setNetworkType(networkType);

        strategySettingsService.save(s);

        // -------------- Сохраняем ExchangeSettings ----------------
        ExchangeSettings ex = exchangeSettingsService.getOrCreate(chatId, exchangeName, networkType);

        ex.setApiKey(params.get("apiKey"));
        ex.setApiSecret(params.get("apiSecret"));
        ex.setPassphrase(params.get("passphrase"));

        boolean hasKeys =
                ex.getApiKey() != null && !ex.getApiKey().isBlank() &&
                ex.getApiSecret() != null && !ex.getApiSecret().isBlank();

        boolean connectionOk = false;

        if (exchangeName.equalsIgnoreCase("BINANCE") && hasKeys) {
            BinanceConnectionStatus diag = exchangeSettingsService.testConnectionDetailed(ex);
            connectionOk = (diag != null && diag.isOk());
        } else {
            connectionOk = hasKeys && exchangeSettingsService.testConnection(ex);
        }

        ex.setEnabled(connectionOk);
        exchangeSettingsService.save(ex);

        log.info("💾 Saved exchange settings: {}@{}, enabled={}",
                exchangeName, networkType, ex.isEnabled());

        // -------------- РЕДИРЕКТ НА ТУ ЖЕ ВКЛАДКУ ----------------
        return "redirect:/strategies/" + type + "/unified-settings"
               + "?chatId=" + chatId
               + "&exchange=" + exchangeName
               + "&network=" + networkType
               + "&tab=" + tab;
    }

    // =========================================================================
    // REAL FEE API
    // =========================================================================
    @GetMapping("/real-fee")
    @ResponseBody
    public Map<String, Object> getRealFee(
            @RequestParam("chatId") long chatId,
            @RequestParam("exchange") String exchange,
            @RequestParam("network") NetworkType network
    ) {

        ExchangeSettings settings =
                exchangeSettingsService.getOrCreate(chatId, exchange, network);

        RealFeeService.FeeResult info =
                realFeeService.loadRealFee(chatId, network);

        if (!info.ok()) {
            return Map.of(
                    "ok", false,
                    "message", info.error() != null && !info.error().isBlank()
                            ? info.error()
                            : "Не удалось получить комиссию. Используем стандартную.",
                    "fee", 0.1
            );
        }

        return Map.of(
                "ok", true,
                "vipLevel", info.vipLevel(),
                "bnb", info.hasBnb(),
                "maker", info.maker(),
                "taker", info.taker(),
                "fee", info.taker()
        );
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
