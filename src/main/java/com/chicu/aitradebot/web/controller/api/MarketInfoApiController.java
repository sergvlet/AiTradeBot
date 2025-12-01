package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.market.dto.MarketOverviewDto;
import com.chicu.aitradebot.market.dto.SymbolInfoDto;
import com.chicu.aitradebot.market.service.MarketInfoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;

/**
 * 🌐 API для вкладки «Торговля»
 * Быстрая выдача из кэша MarketInfoServiceImpl
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/market")
public class MarketInfoApiController {

    private final MarketInfoService marketInfoService;

    // ---------------------------------------------------------------------
    // 1. ОБЗОР ВСЕГО РЫНКА
    // ---------------------------------------------------------------------
    @GetMapping("/overview")
    public MarketOverviewDto getOverview(
            @RequestParam("exchange") String exchange,
            @RequestParam("network") NetworkType network
    ) {
        log.debug("📊 [API] GET overview {} @ {}", exchange, network);
        return marketInfoService.getOverview(exchange, network);
    }

    // ---------------------------------------------------------------------
    // 2. ПОИСК (МГНОВЕННО, ИЗ КЭША)
    // ---------------------------------------------------------------------
    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String exchange,
            @RequestParam NetworkType network,
            @RequestParam("q") String query
    ) {
        try {
            List<SymbolInfoDto> list =
                    marketInfoService.searchSymbols(exchange, network, query);

            if (list == null || list.isEmpty()) {
                // чтобы UI не ломался — отдаём пустой список
                return ResponseEntity.ok(List.of());
            }

            // Быстрая сортировка: объём ↓, затем изменение ↓
            list = list.stream()
                    .sorted(
                            Comparator.comparing(SymbolInfoDto::getVolume).reversed()
                                    .thenComparing(
                                            s -> Math.abs(s.getChangePct()),
                                            Comparator.reverseOrder()
                                    )
                    )
                    .toList();

            return ResponseEntity.ok(list);

        } catch (Exception e) {
            log.error("❌ Ошибка поиска пар: {}", e.getMessage());

            // UI всегда должен получить список — даже пустой
            return ResponseEntity.ok(List.of());
        }
    }

    // ---------------------------------------------------------------------
    // 3. КОНКРЕТНЫЙ СИМВОЛ
    // ---------------------------------------------------------------------
    @GetMapping("/symbol")
    public SymbolInfoDto getSymbolInfo(
            @RequestParam("exchange") String exchange,
            @RequestParam("network") NetworkType network,
            @RequestParam("symbol") String symbol
    ) {
        log.debug("ℹ [API] SYMBOL {} {} @ {}", symbol, exchange, network);
        return marketInfoService.getSymbolInfo(exchange, network, symbol);
    }
}
