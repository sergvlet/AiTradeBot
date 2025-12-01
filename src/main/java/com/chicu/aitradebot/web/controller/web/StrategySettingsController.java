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

    /** Дефолтные таймфреймы, если биржа ничего не вернула */
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
            Model model
    ) {

        StrategyType strategyType = StrategyType.valueOf(type);

        // 1) Загружаем стратегию с auto-create
        StrategySettings strategy = strategySettingsService.getOrCreate(chatId, strategyType);

        // 2) Список поддерживаемых бирж
        List<String> availableExchanges = List.of("BINANCE", "BYBIT", "OKX");

        // 3) Все сохранённые ключи пользователя
        List<ExchangeSettings> userExchanges =
                exchangeSettingsService.findAllByChatId(chatId);

        // ----------------------------------------------------------
        // 4) Определяем выбранную биржу + сеть
        // ----------------------------------------------------------
        String selectedExchange = exchangeParam;
        NetworkType selectedNetwork = networkParam;

        if (selectedExchange == null) {
            if (strategy.getExchangeName() != null) {
                selectedExchange = strategy.getExchangeName();
            } else {
                Optional<ExchangeSettings> anyEx = userExchanges.stream().findFirst();
                selectedExchange = anyEx.map(ExchangeSettings::getExchange)
                        .orElse("BINANCE");
            }
        }

        if (selectedNetwork == null) {
            if (strategy.getNetworkType() != null) {
                selectedNetwork = strategy.getNetworkType();
            } else {
                Optional<ExchangeSettings> anyEx = userExchanges.stream().findFirst();
                selectedNetwork = anyEx.map(ExchangeSettings::getNetwork)
                        .orElse(NetworkType.TESTNET);
            }
        }

        // ----------------------------------------------------------
        // 5) Загружаем ExchangeSettings под выбранную биржу/сеть
        // ----------------------------------------------------------
        ExchangeSettings exchangeSettings =
                exchangeSettingsService.getOrCreate(chatId, selectedExchange, selectedNetwork);

        // ----------------------------------------------------------
        // 6) Проверяем наличие ключей
        // ----------------------------------------------------------
        boolean hasKeys =
                !isBlank(exchangeSettings.getApiKey()) &&
                !isBlank(exchangeSettings.getApiSecret());

        // ----------------------------------------------------------
        // 7) ДЕТАЛЬНАЯ диагностика (только Binance)
        // ----------------------------------------------------------
        BinanceConnectionStatus diagnostics = null;
        boolean connectionOk = false;

        if (selectedExchange.equalsIgnoreCase("BINANCE") && hasKeys) {

            diagnostics = exchangeSettingsService.testConnectionDetailed(exchangeSettings);

            if (diagnostics != null) {
                connectionOk = diagnostics.isOk();
            }

            log.info("🔍 Diagnostics for BINANCE {}: {}", selectedNetwork, diagnostics);

        } else {
            connectionOk = hasKeys && exchangeSettingsService.testConnection(exchangeSettings);
        }

        // ----------------------------------------------------------
        // 8) Баланс USDT + доступные таймфреймы
        // ----------------------------------------------------------
        double usdtBalance = 0.0;
        List<String> availableTimeframes = new ArrayList<>(DEFAULT_TIMEFRAMES);

        try {
            ExchangeClient client = clientFactory.get(selectedExchange, selectedNetwork);

            // баланс
            var bal = client.getBalance(chatId, "USDT");
            if (bal != null) {
                usdtBalance = bal.free();
            }

            // таймфреймы от биржи (если реализовано)
            List<String> fromClient = client.getAvailableTimeframes();
            if (fromClient != null && !fromClient.isEmpty()) {
                availableTimeframes = fromClient;
            }
        } catch (Exception e) {
            log.warn("Не удалось загрузить баланс/таймфреймы: {}", e.getMessage());
        }

        // ----------------------------------------------------------
        // 9) Передаём всё в UI
        // ----------------------------------------------------------
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

        // пока отключено — подставишь позже
        model.addAttribute("dynamicFields", Map.of());

        log.debug(
                "⚙ Unified settings loaded: chatId={}, strategy={}, exchange={}@{}, enabled={}, usdt={}, tf={}",
                chatId, strategyType, selectedExchange, selectedNetwork, connectionOk, usdtBalance, availableTimeframes
        );

        return "strategies/unified-settings";
    }

    // =========================================================================
    // POST — СОХРАНЕНИЕ НАСТРОЕК
    // =========================================================================
    @PostMapping
    public String saveSettings(
            @PathVariable("type") String type,
            @RequestParam("chatId") long chatId,
            @ModelAttribute("strategy") StrategySettings form,
            @RequestParam Map<String, String> params
    ) {

        StrategyType strategyType = StrategyType.valueOf(type);

        // 1) Биржа / сеть из формы
        String exchangeName = params.get("exchange");
        String networkStr = params.get("network");
        NetworkType networkType = NetworkType.valueOf(networkStr);

        // 2) Загружаем реальную сущность
        StrategySettings s = strategySettingsService.getOrCreate(chatId, strategyType);

        s.setSymbol(form.getSymbol());
        s.setTimeframe(form.getTimeframe());               // <- универсальный формат (1m, 1h и т.д.)
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

        // 3) Обновляем ExchangeSettings
        ExchangeSettings ex =
                exchangeSettingsService.getOrCreate(chatId, exchangeName, networkType);

        ex.setApiKey(params.get("apiKey"));
        ex.setApiSecret(params.get("apiSecret"));
        ex.setPassphrase(params.get("passphrase"));

        boolean hasKeys =
                !isBlank(ex.getApiKey()) &&
                !isBlank(ex.getApiSecret());

        boolean connectionOk = false;

        if (exchangeName.equalsIgnoreCase("BINANCE") && hasKeys) {
            BinanceConnectionStatus diag =
                    exchangeSettingsService.testConnectionDetailed(ex);
            connectionOk = (diag != null && diag.isOk());
        } else {
            connectionOk = hasKeys && exchangeSettingsService.testConnection(ex);
        }

        ex.setEnabled(connectionOk);
        exchangeSettingsService.save(ex);

        log.info("💾 Saved exchange settings: {}@{}, enabled={}",
                exchangeName, networkType, ex.isEnabled());

        return "redirect:/strategies/" + type + "/unified-settings"
               + "?chatId=" + chatId
               + "&exchange=" + exchangeName
               + "&network=" + networkType;
    }

    // =========================================================================
    // REAL FEE
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
