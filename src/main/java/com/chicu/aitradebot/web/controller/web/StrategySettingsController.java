package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
@RequestMapping("/strategies/{type}/unified-settings")
public class StrategySettingsController {

    private final StrategySettingsService strategySettingsService;
    private final ExchangeSettingsService exchangeSettingsService;

    // ===============================================================
    // GET — открыть страницу настройки стратегии
    // ===============================================================
    @GetMapping
    public String openSettings(
            @PathVariable("type") String type,
            @RequestParam("chatId") long chatId,
            Model model
    ) {
        StrategyType strategyType = StrategyType.valueOf(type);

        // --- 1. Грузим StrategySettings ---
        StrategySettings strategy = strategySettingsService.getOrCreate(chatId, strategyType);

        // --- 2. Грузим ExchangeSettings (по умолчанию BINANCE + MAINNET) ---
        ExchangeSettings exchangeSettings =
                exchangeSettingsService.getOrCreate(chatId, "BINANCE", NetworkType.MAINNET);

        // --- 3. Тест подключения к бирже ---
        boolean connectionOk = exchangeSettingsService.testConnection(exchangeSettings);

        // --- 4. Генерируем динамические поля стратегии ---
        Map<String, Object> dynamicFields = Map.of(); // временно пусто

        // --- 5. Передаём всё в шаблон ---
        model.addAttribute("chatId", chatId);
        model.addAttribute("type", strategyType);
        model.addAttribute("strategy", strategy);

        model.addAttribute("exchangeSettings", exchangeSettings);
        model.addAttribute("connectionOk", connectionOk);

        model.addAttribute("dynamicFields", dynamicFields);

        log.debug("⚙ Loaded settings: chatId={}, type={}, strategyId={}, exchangeId={}",
                chatId, strategyType, strategy.getId(), exchangeSettings.getId());

        return "strategies/unified-settings";
    }

    // ===============================================================
    // POST — сохранить настройки
    // ===============================================================
    @PostMapping
    public String saveSettings(
            @PathVariable("type") String type,
            @RequestParam("chatId") long chatId,
            @ModelAttribute("strategy") StrategySettings strategy,
            @RequestParam Map<String, String> params
    ) {
        StrategyType strategyType = StrategyType.valueOf(type);
        strategy.setType(strategyType);

        // --- 1. Сохраняем StrategySettings ---
        strategySettingsService.save(strategy);

        // --- 2. Сохраняем ExchangeSettings ---
        ExchangeSettings ex = new ExchangeSettings();
        ex.setChatId(chatId);
        ex.setExchange(params.get("exchange"));
        ex.setNetwork(NetworkType.valueOf(params.get("network")));
        ex.setApiKey(params.get("apiKey"));
        ex.setApiSecret(params.get("apiSecret"));
        ex.setPassphrase(params.get("passphrase"));
        ex.setEnabled(true);

        exchangeSettingsService.save(ex);

        return "redirect:/strategies";
    }
}
