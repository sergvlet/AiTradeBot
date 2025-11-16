package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/exchange")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ExchangeSymbolsApiController {

    private final ExchangeClientFactory clientFactory;

    /**
     * 📜 Возвращает список доступных торговых пар для выбранной биржи и сети.
     * Пример: GET /api/exchange/symbols?exchange=BINANCE&networkType=TESTNET
     */
    @GetMapping("/symbols")
    public ResponseEntity<?> getSymbols(
            @RequestParam String exchange,
            @RequestParam(defaultValue = "MAINNET") NetworkType networkType
    ) {
        try {
            ExchangeClient client = clientFactory.getClient(exchange, networkType); // ✅ исправлено
            List<String> symbols = client.getAllSymbols();
            log.info("📊 Загружено {} пар с {} ({})", symbols.size(), exchange, networkType);
            return ResponseEntity.ok(symbols);
        } catch (Exception e) {
            log.error("❌ Ошибка загрузки пар: {} / {} — {}", exchange, networkType, e.getMessage());
            return ResponseEntity.internalServerError().body(List.of());
        }
    }
}
