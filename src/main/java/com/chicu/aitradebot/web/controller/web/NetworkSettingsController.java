package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.model.ApiKeyDiagnostics;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Controller
@RequiredArgsConstructor
@RequestMapping("/strategies")
public class NetworkSettingsController {

    private final ExchangeSettingsService exchangeSettingsService;

    // =====================================================
    // 🔍 DIAGNOSE (AJAX)
    // =====================================================
    @PostMapping("/network/diagnose")
    @ResponseBody
    public ApiKeyDiagnostics diagnose(
            @RequestParam long chatId,
            @RequestParam String exchange,
            @RequestParam NetworkType network
    ) {

        ExchangeSettings settings =
                exchangeSettingsService.getOrCreate(chatId, exchange, network);

        if (!settings.hasKeys()) {
            return ApiKeyDiagnostics.notConfigured(
                    exchange,
                    "API keys not set"
            );
        }

        return exchangeSettingsService.testConnectionDetailed(settings);
    }

    // =====================================================
    // 🔑 SAVE API KEYS
    // =====================================================
    @PostMapping("/{type}/network/keys")
    public String saveKeys(
            @PathVariable String type,
            @RequestParam long chatId,
            @RequestParam String exchange,
            @RequestParam NetworkType network,
            @RequestParam(required = false) String apiKey,
            @RequestParam(required = false) String apiSecret,
            @RequestParam(required = false) String passphrase
    ) {

        exchangeSettingsService.saveKeys(
                chatId,
                exchange,
                network,
                trim(apiKey),
                trim(apiSecret),
                trim(passphrase)
        );

        return "redirect:/strategies/" + type +
               "/config?chatId=" + chatId +
               "&exchange=" + exchange +
               "&network=" + network +
               "&tab=tab-network";
    }

    private String trim(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
