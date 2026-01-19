package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.exchange.model.ApiKeyDiagnostics;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/exchange/diagnostics")
public class ExchangeDiagnosticsApiController {

    private final ExchangeSettingsService settingsService;
    private final ExchangeClientFactory clientFactory;

    /**
     * Универсальная AJAX-диагностика ключей Binance.
     *
     * GET /api/exchange/diagnostics/binance?chatId=1&network=MAINNET
     */
    @GetMapping("/binance")
    public ApiKeyDiagnostics testBinance(
            @RequestParam long chatId,
            @RequestParam NetworkType network
    ) {

        // Ищем настройки BINANCE для указанного chatId и сети
        List<ExchangeSettings> all = settingsService.findAllByChatId(chatId);

        Optional<ExchangeSettings> opt = all.stream()
                .filter(es -> "BINANCE".equalsIgnoreCase(es.getExchange()))
                .filter(es -> es.getNetwork() == network)
                .findFirst();

        if (opt.isEmpty()) {

            log.warn("⚠ Нет настроек BINANCE {} для chatId={}", network, chatId);

            return ApiKeyDiagnostics.builder()
                    .exchange("BINANCE")
                    .ok(false)
                    .message("Нет настроек BINANCE/" + network + " для chatId=" + chatId)
                    .apiKeyValid(false)
                    .secretValid(false)
                    .signatureValid(false)
                    .accountReadable(false)
                    .tradingAllowed(false)
                    .ipAllowed(false)
                    .networkOk(true)
                    .build();
        }

        ExchangeSettings settings = opt.get();

        // Вызываем новую диагностику через сервис
        ApiKeyDiagnostics diag = settingsService.testConnectionDetailed(settings);

        log.info("🔍 Diagnostics BINANCE@{} for chatId={} → {}",
                network, chatId, diag);

        return diag;
    }
}
