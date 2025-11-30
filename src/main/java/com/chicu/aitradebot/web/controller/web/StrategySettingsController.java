package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.exchange.model.BinanceConnectionStatus;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
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

        // 1) Загружаем стратегию
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

        if (selectedExchange == null || selectedNetwork == null) {

            Optional<ExchangeSettings> active = userExchanges.stream()
                    .filter(ExchangeSettings::isEnabled)
                    .findFirst();

            ExchangeSettings picked = active.orElse(
                    userExchanges.isEmpty() ? null : userExchanges.get(0)
            );

            if (picked != null) {
                if (selectedExchange == null) selectedExchange = picked.getExchange();
                if (selectedNetwork == null) selectedNetwork = picked.getNetwork();
            }
        }

        if (selectedExchange == null) selectedExchange = "BINANCE";
        if (selectedNetwork == null) selectedNetwork = NetworkType.MAINNET;

        // ----------------------------------------------------------
        // 5) ExchangeSettings строго под выбранную биржу/сеть
        // ----------------------------------------------------------
        ExchangeSettings exchangeSettings =
                exchangeSettingsService.getOrCreate(chatId, selectedExchange, selectedNetwork);

        // ----------------------------------------------------------
        // 6) Проверяем ключи
        // ----------------------------------------------------------
        boolean hasKeys =
                notBlank(exchangeSettings.getApiKey()) &&
                notBlank(exchangeSettings.getApiSecret());

        // ----------------------------------------------------------
        // 7) Проверка подключения
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
        // 8) Передаём всё в UI
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
        model.addAttribute("dynamicFields", Map.of());

        log.debug(
                "⚙ Unified settings loaded: chatId={}, strategy={}, exchange={}@{}, enabled={}",
                chatId, strategyType, selectedExchange, selectedNetwork, connectionOk
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
            @ModelAttribute("strategy") StrategySettings posted,
            @RequestParam Map<String, String> params
    ) {

        StrategyType strategyType = StrategyType.valueOf(type);

        // Загружаем старую стратегию (чтобы не затронуть active!)
        StrategySettings existing = strategySettingsService.getOrCreate(chatId, strategyType);

        boolean oldActive = existing.isActive();

        // Обновляем поля
        existing.setSymbol(posted.getSymbol());
        existing.setTimeframe(posted.getTimeframe());
        existing.setCachedCandlesLimit(posted.getCachedCandlesLimit());
        existing.setCapitalUsd(posted.getCapitalUsd());
        existing.setCommissionPct(posted.getCommissionPct());
        existing.setReinvestProfit(posted.isReinvestProfit());
        existing.setTakeProfitPct(posted.getTakeProfitPct());
        existing.setStopLossPct(posted.getStopLossPct());
        existing.setRiskPerTradePct(posted.getRiskPerTradePct());
        existing.setDailyLossLimitPct(posted.getDailyLossLimitPct());
        existing.setLeverage(posted.getLeverage());

        // ВОССТАНАВЛИВАЕМ ПРЕЖНЕЕ active
        existing.setActive(oldActive);

        // Сохраняем
        strategySettingsService.save(existing);

        // ----------------------------------------------------------
        // Обновляем данные биржи
        // ----------------------------------------------------------
        String exchangeName = params.get("exchange");
        NetworkType networkType = NetworkType.valueOf(params.get("network"));

        ExchangeSettings ex =
                exchangeSettingsService.getOrCreate(chatId, exchangeName, networkType);

        ex.setApiKey(params.get("apiKey"));
        ex.setApiSecret(params.get("apiSecret"));
        ex.setPassphrase(params.get("passphrase"));

        boolean hasKeys =
                notBlank(ex.getApiKey()) &&
                        notBlank(ex.getApiSecret());

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

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
