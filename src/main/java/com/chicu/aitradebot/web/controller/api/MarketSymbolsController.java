package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.market.model.SymbolDescriptor;
import com.chicu.aitradebot.market.model.SymbolListMode;
import com.chicu.aitradebot.market.service.MarketSymbolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Slf4j
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

        String quoteAsset = firstNonBlankUpper(accountAsset, asset);

        if (quoteAsset == null) {
            // ✅ фронту удобнее получить пустой список, чем 400 (иначе dropdown ломается)
            return ResponseEntity.ok(List.of());
        }

        SymbolListMode safeMode = parseMode(mode);

        try {
            List<SymbolDescriptor> result = marketSymbolService.getSymbols(ex, net, quoteAsset, safeMode);

            return ResponseEntity.ok()
                    // ✅ чтобы UI не “залипал” навсегда, но и не ддосил
                    .cacheControl(CacheControl.maxAge(Duration.ofSeconds(10)).cachePrivate())
                    .body(result == null ? List.of() : result);

        } catch (Exception e) {
            log.warn("symbols failed ex={} net={} asset={} mode={}: {}", ex, net, quoteAsset, safeMode, e.toString());
            // ✅ не роняем UI
            return ResponseEntity.ok(List.of());
        }
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

        String quoteAsset = firstNonBlankUpper(accountAsset, asset);
        String sym = normalizeUpperOrNull(symbol);

        if (quoteAsset == null || sym == null) {
            // ✅ опять же: UI проще, когда ответ “пусто”, а не 400
            return ResponseEntity.ok(null);
        }

        try {
            SymbolDescriptor info = marketSymbolService.getSymbolInfo(ex, net, quoteAsset, sym);

            if (info == null) return ResponseEntity.notFound().build();

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(Duration.ofSeconds(10)).cachePrivate())
                    .body(info);

        } catch (Exception e) {
            log.warn("symbolInfo failed ex={} net={} asset={} sym={}: {}", ex, net, quoteAsset, sym, e.toString());
            return ResponseEntity.notFound().build();
        }
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
        // ✅ дефолт лучше TESTNET, чтобы не “улетать” в MAINNET случайно
        if (raw == null || raw.isBlank()) return NetworkType.TESTNET;

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

    private static String normalizeUpperOrNull(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private static String firstNonBlankUpper(String a, String b) {
        String x = normalizeUpperOrNull(a);
        if (x != null) return x;
        return normalizeUpperOrNull(b);
    }
}
