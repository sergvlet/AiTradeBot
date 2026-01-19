package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.market.model.SymbolDescriptor;
import com.chicu.aitradebot.market.model.SymbolListMode;
import com.chicu.aitradebot.market.service.MarketSymbolService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/market")
public class MarketSymbolsController {

    private final MarketSymbolService marketSymbolService;

    /**
     * ✅ Список символов для dropdown
     * GET /api/market/symbols?exchange=BINANCE&network=TESTNET&accountAsset=USDT&mode=POPULAR
     * Поддерживаем alias параметра "asset".
     * Сеть парсим сами (чтобы не падало 400 при testnet/TESTNET/ пробелах).
     */
    @GetMapping("/symbols")
    public ResponseEntity<List<SymbolDescriptor>> symbols(
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String network,
            @RequestParam(required = false) String accountAsset,
            @RequestParam(required = false) String asset,
            @RequestParam(required = false, defaultValue = "POPULAR") String mode
    ) {

        String ex = normalizeExchange(exchange);
        NetworkType net = parseNetwork(network);

        String quoteAsset = normalizeAssetOrNull(accountAsset);
        if (quoteAsset == null) quoteAsset = normalizeAssetOrNull(asset);

        if (quoteAsset == null) {
            return ResponseEntity.badRequest().body(List.of());
        }

        SymbolListMode safeMode = parseMode(mode);

        List<SymbolDescriptor> result = marketSymbolService.getSymbols(
                ex,
                net,
                quoteAsset,
                safeMode
        );

        return ResponseEntity.ok(result);
    }

    /**
     * ✅ Информация по выбранному символу (для "Ограничения биржи")
     * GET /api/market/symbol-info?exchange=BINANCE&network=TESTNET&accountAsset=USDT&symbol=BTCUSDT
     * Поддерживаем alias параметра "asset".
     */
    @GetMapping("/symbol-info")
    public ResponseEntity<SymbolDescriptor> symbolInfo(
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String network,
            @RequestParam(required = false) String accountAsset,
            @RequestParam(required = false) String asset,
            @RequestParam(required = false) String symbol
    ) {

        String ex = normalizeExchange(exchange);
        NetworkType net = parseNetwork(network);

        String quoteAsset = normalizeAssetOrNull(accountAsset);
        if (quoteAsset == null) quoteAsset = normalizeAssetOrNull(asset);

        String sym = (symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT));

        if (quoteAsset == null || sym.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        SymbolDescriptor info = marketSymbolService.getSymbolInfo(
                ex,
                net,
                quoteAsset,
                sym
        );

        if (info == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(info);
    }

    // =====================================================
    // helpers
    // =====================================================

    private static SymbolListMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) return SymbolListMode.POPULAR;
        try {
            return SymbolListMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return SymbolListMode.POPULAR;
        }
    }

    private static NetworkType parseNetwork(String raw) {
        if (raw == null || raw.isBlank()) return NetworkType.MAINNET;
        String n = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return NetworkType.valueOf(n);
        } catch (Exception ignored) {
            // часто прилетает "TEST", "DEMO", "MAIN"
            if (n.contains("TEST") || n.contains("DEMO")) return NetworkType.TESTNET;
            return NetworkType.MAINNET;
        }
    }

    private static String normalizeExchange(String exchange) {
        if (exchange == null) return "BINANCE";
        String ex = exchange.trim().toUpperCase(Locale.ROOT);
        return ex.isEmpty() ? "BINANCE" : ex;
    }

    /** Возвращает NULL если пусто */
    private static String normalizeAssetOrNull(String asset) {
        if (asset == null) return null;
        String a = asset.trim().toUpperCase(Locale.ROOT);
        return a.isEmpty() ? null : a;
    }
}
