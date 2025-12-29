package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.market.model.SymbolDescriptor;
import com.chicu.aitradebot.market.model.SymbolListMode;
import com.chicu.aitradebot.market.service.MarketSymbolService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/market")
public class MarketSymbolsController {

    private final MarketSymbolService marketSymbolService;

    @GetMapping("/symbols")
    public List<SymbolDescriptor> symbols(
            @RequestParam String exchange,
            @RequestParam NetworkType network,
            @RequestParam String accountAsset,
            @RequestParam(defaultValue = "POPULAR") String mode
    ) {
        SymbolListMode safeMode = parseMode(mode);

        return marketSymbolService.getSymbols(
                exchange,
                network,
                accountAsset,
                safeMode
        );
    }

    private SymbolListMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return SymbolListMode.POPULAR;
        }
        try {
            return SymbolListMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return SymbolListMode.POPULAR;
        }
    }
}
