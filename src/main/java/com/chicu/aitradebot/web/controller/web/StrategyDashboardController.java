package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Controller
@RequiredArgsConstructor
@RequestMapping("/strategies")
public class StrategyDashboardController {

    private final AiStrategyOrchestrator orchestrator;

    @GetMapping("/{type}/strategy_dashboard")
    public String strategyDashboardPage(@PathVariable("type") String type,
                                        @RequestParam("chatId") Long chatId,
                                        @RequestParam(value = "symbol", required = false) String symbol,
                                        Model model) {

        StrategyRunInfo info = orchestrator.getStatus(chatId, StrategyType.valueOf(type));

        if (info == null) {
            log.warn("StrategyRunInfo is null for chatId={} type={}", chatId, type);
        }

        model.addAttribute("page", "strategy_dashboard");
        model.addAttribute("type", type);
        model.addAttribute("chatId", chatId);
        model.addAttribute("symbol",
                symbol != null ? symbol : (info != null ? info.getSymbol() : "BTCUSDT"));
        model.addAttribute("info", info);
        return "layout/app";
    }
}
