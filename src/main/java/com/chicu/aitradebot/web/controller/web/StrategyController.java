package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.service.UserProfileService;
import com.chicu.aitradebot.web.facade.WebStrategyFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/strategies")
@RequiredArgsConstructor
@Slf4j
public class StrategyController {

    private final WebStrategyFacade strategyFacade;
    private final UserProfileService userProfileService;

    // ================================================================
    // 🌍 DEFAULT CONTEXT (WEB UI)
    // ================================================================
    private static final String DEFAULT_EXCHANGE = "BINANCE";
    private static final NetworkType DEFAULT_NETWORK = NetworkType.MAINNET;

    // ================================================================
    // 📋 СПИСОК СТРАТЕГИЙ
    // ================================================================
    @GetMapping
    public String strategies(Model model,
                             @RequestParam(required = false) Long chatIdParam) {

        Long chatId = (chatIdParam != null)
                ? chatIdParam
                : resolveCurrentChatIdOrThrow();

        model.addAttribute("active", "strategies");
        model.addAttribute("pageTitle", "Стратегии");
        model.addAttribute("page", "strategies");

        model.addAttribute(
                "strategies",
                strategyFacade.getStrategies(
                        chatId,
                        DEFAULT_EXCHANGE,
                        DEFAULT_NETWORK
                )
        );

        model.addAttribute("chatId", chatId);
        model.addAttribute("exchange", DEFAULT_EXCHANGE);
        model.addAttribute("network", DEFAULT_NETWORK);

        return "layout/app";
    }

    // ================================================================
    // 📊 ДАШБОРД СТРАТЕГИИ
    // ================================================================
    @GetMapping("/{type}/dashboard")
    public String strategyDashboard(@PathVariable StrategyType type,
                                    @RequestParam(required = false) Long chatIdParam,
                                    @RequestParam(required = false) String symbol,
                                    Model model) {

        Long chatId = (chatIdParam != null)
                ? chatIdParam
                : resolveCurrentChatIdOrThrow();

        var all = strategyFacade.getStrategies(
                chatId,
                DEFAULT_EXCHANGE,
                DEFAULT_NETWORK
        );

        var uiOpt = all.stream()
                .filter(s -> type.name().equals(s.type()))
                .findFirst();

        if (uiOpt.isEmpty()) {
            model.addAttribute("pageTitle", "Ошибка");
            model.addAttribute("error", "Стратегия не найдена.");
            return "error";
        }

        var ui = uiOpt.get();

        String finalSymbol = (symbol != null && !symbol.isBlank())
                ? symbol
                : ui.symbol();

        model.addAttribute("active", "strategies");
        model.addAttribute("pageTitle", "Стратегия — " + type);
        model.addAttribute("chatId", chatId);
        model.addAttribute("type", type);
        model.addAttribute("symbol", finalSymbol);
        model.addAttribute("info", ui);
        model.addAttribute("page", "strategy-dashboard");

        model.addAttribute("exchange", DEFAULT_EXCHANGE);
        model.addAttribute("network", DEFAULT_NETWORK);

        return "layout/app";
    }

    // ================================================================
    // ▶️ START / STOP / TOGGLE
    // ================================================================
    @PostMapping("/toggle")
    public String toggleStrategy(@RequestParam Long chatId,
                                 @RequestParam StrategyType type) {

        strategyFacade.toggle(
                chatId,
                type,
                DEFAULT_EXCHANGE,
                DEFAULT_NETWORK
        );

        return "redirect:/strategies?chatId=" + chatId;
    }

    @PostMapping("/start")
    public String startStrategy(@RequestParam Long chatId,
                                @RequestParam StrategyType type) {

        strategyFacade.start(
                chatId,
                type,
                DEFAULT_EXCHANGE,
                DEFAULT_NETWORK
        );

        return "redirect:/strategies?chatId=" + chatId;
    }

    @PostMapping("/stop")
    public String stopStrategy(@RequestParam Long chatId,
                               @RequestParam StrategyType type) {

        strategyFacade.stop(
                chatId,
                type,
                DEFAULT_EXCHANGE,
                DEFAULT_NETWORK
        );

        return "redirect:/strategies?chatId=" + chatId;
    }

    // ================================================================
    // 🎯 HELPERS
    // ================================================================
    private Long resolveCurrentChatIdOrThrow() {
        Long chatId = userProfileService.getCurrentChatId();
        if (chatId == null || chatId <= 0) {
            throw new IllegalStateException("ChatId не найден (пользователь не определён)");
        }
        return chatId;
    }
}
