package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.web.facade.WebStrategyFacade;
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

    private final WebStrategyFacade webStrategyFacade;
    private final StrategySettingsService settingsService;

    @GetMapping("/{type}/dashboard")
    public String dashboard(
            @PathVariable StrategyType type,
            @RequestParam Long chatId,
            @RequestParam String symbol,
            Model model
    ) {
        log.info("📊 Открытие дашборда {} chatId={} symbol={}", type, chatId, symbol);

        // 1. Настройки стратегии
        var settings = settingsService.getOrCreate(chatId, type);

        // 2. Текущее состояние стратегии
        StrategyRunInfo info = webStrategyFacade.getRunInfo(chatId, type);

        // 3. На данном этапе trades временно ставим null — UI это поддерживает
        model.addAttribute("trades", null);

        // 4. Передаём параметры в UI
        model.addAttribute("chatId", chatId);
        model.addAttribute("symbol", settings.getSymbol());
        model.addAttribute("type", type);
        model.addAttribute("info", info);

        return "strategies/dashboard";
    }
}
