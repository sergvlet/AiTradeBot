package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.binance.BinanceExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.exchange.model.BinanceConnectionStatus;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
     * AJAX-диагностика ключей Binance.
     *
     * GET /api/exchange/diagnostics/binance?chatId=1&exchange=BINANCE&network=MAINNET
     */
    @GetMapping("/binance")
    public BinanceConnectionStatus testBinance(@RequestParam long chatId,
                                               @RequestParam String exchange,
                                               @RequestParam NetworkType network) {

        // 1. Ищем ключи в БД
        List<ExchangeSettings> all = settingsService.findAllByChatId(chatId);

        Optional<ExchangeSettings> opt = all.stream()
                .filter(ExchangeSettings::isEnabled)
                .filter(es -> "BINANCE".equalsIgnoreCase(es.getExchange()))
                .filter(es -> es.getNetwork() == network)
                .findFirst();

        if (opt.isEmpty()) {
            log.warn("⚠ Нет настроек Binance для chatId={}, exchange={}, network={}", chatId, exchange, network);
            return BinanceConnectionStatus.builder()
                    .ok(false)
                    .keyValid(false)
                    .secretValid(false)
                    .readingEnabled(false)
                    .tradingEnabled(false)
                    .ipAllowed(false)
                    .networkMismatch(false)
                    .message("Нет настроек BINANCE/" + network + " для chatId=" + chatId)
                    .reasons(List.of("Сначала сохраните ключи Binance для выбранной сети."))
                    .build();
        }

        ExchangeSettings s = opt.get();

        // 2. Получаем клиента Binance для нужной сети из фабрики
        ExchangeClient client = clientFactory.getClient("BINANCE", network);

        if (!(client instanceof BinanceExchangeClient binanceClient)) {
            log.error("❌ Клиент для BINANCE не является BinanceExchangeClient: {}", client.getClass().getName());
            return BinanceConnectionStatus.builder()
                    .ok(false)
                    .keyValid(false)
                    .secretValid(false)
                    .readingEnabled(false)
                    .tradingEnabled(false)
                    .ipAllowed(false)
                    .networkMismatch(false)
                    .message("Внутренняя ошибка конфигурации Binance-клиента")
                    .reasons(List.of("Проверьте конфигурацию ExchangeClientFactory."))
                    .build();
        }

        boolean isTestnet = network == NetworkType.TESTNET;

        BinanceConnectionStatus status = binanceClient.extendedTestConnection(
                s.getApiKey(),
                s.getApiSecret(),
                isTestnet
        );

        log.info("🔍 Diagnostics BINANCE@{} for chatId={}: {}", network, chatId, status);

        return status;
    }
}
