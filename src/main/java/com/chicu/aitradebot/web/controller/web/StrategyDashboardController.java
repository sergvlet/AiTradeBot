package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Controller
@RequestMapping("/strategies")
public class StrategyDashboardController {

    @GetMapping("/{type}/dashboard")
    public String dashboard(
            @PathVariable("type") StrategyType type,
            @RequestParam("chatId") Long chatId,
            @RequestParam("symbol") String symbol,
            Model model
    ) {
        log.info("📊 Открытие дашборда стратегии {} chatId={} symbol={}", type, chatId, symbol);

        // эти атрибуты используются в шаблоне и JS (data-атрибуты)
        model.addAttribute("chatId", chatId);
        model.addAttribute("symbol", symbol);
        model.addAttribute("strategyType", type);

        // можно позже добавить сюда статистику, состояние стратегии и т.п.
        // model.addAttribute("runInfo", runInfoService.get(...));

        // ищется шаблон: src/main/resources/templates/strategies/dashboard.html
        return "strategies/dashboard";
    }
}
