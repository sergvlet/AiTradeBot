package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.orchestrator.StrategyFacade;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/strategies")
@RequiredArgsConstructor
@Slf4j
public class StrategyDashboardController {

    private final StrategyFacade strategyFacade;

    @GetMapping("/{type}/dashboard")
    public String dashboard(
            @PathVariable("type") String type,
            @RequestParam long chatId,
            @RequestParam(required = false) String symbol,
            Model model
    ) {

        StrategyType strategyType = StrategyType.valueOf(type.toUpperCase());

        // ---------- SYMBOL ----------
        if (symbol == null || symbol.isBlank()) {
            symbol = strategyFacade.getSymbol(chatId, strategyType);
        }

        // ---------- STRATEGY INFO ----------
        StrategyRunInfo info = strategyFacade.status(chatId, strategyType);

        // ---------- TRADES ----------
        // ⚠️ Метод может возвращать:
        //  - List<OrderEntity>
        //  - List<Order>
        //  - смесь типов
        //
        //  Поэтому делаем универсально:
        List<?> trades = strategyFacade.getTrades(chatId, symbol);

        model.addAttribute("type", strategyType.name());
        model.addAttribute("chatId", chatId);
        model.addAttribute("symbol", symbol);
        model.addAttribute("info", info);

        // Добавляем trades в модель (универсальный список)
        model.addAttribute("trades", trades);

        log.info("📊 DASHBOARD {} chatId={}, symbol={}, trades={}",
                type, chatId, symbol, trades != null ? trades.size() : 0);

        return "strategy-dashboard";
    }
}
